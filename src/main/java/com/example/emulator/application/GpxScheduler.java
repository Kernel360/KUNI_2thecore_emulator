package com.example.emulator.application;

import com.example.emulator.application.dto.EndRequestDto;
import com.example.emulator.application.dto.GpxLogDto;
import com.example.emulator.application.dto.GpxRequestDto;
import com.example.emulator.application.dto.StartRequestDto;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.sql.ast.tree.expression.Star;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Getter
@Slf4j
public class GpxScheduler{
    private final RestTemplate restTemplate; // api 호출을 위함
    private final String addr = "52.78.122.150"; //"localhost";

    private List<String> gpxFile = new ArrayList<>(); // Gpx 파일을 읽어와 저장해두는 리스트
    private List<GpxLogDto> buffer = new ArrayList<>(); // 전송될 GPX 정보들을 저장해두는 리스트
    private int currentIndex = 0; // 읽어야 할 line 번호
    private int endIndex = 0; // 해당 인덱스까지 읽기

    private String carNumber;
    private String loginId;

    private String startTime;
    private String endTime;

    private String finalStartTime = null; // 주행기록 찾는 키가 되는 전체 주행 시작 시간
    private String startLatitude;
    private String startLongitude;

    private String latitude;
    private String longitude;
    private String timestamp;

    // 스케줄러 실행 여부 확인
    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public GpxScheduler(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        // Enable PATCH support for RestTemplate
        this.restTemplate.setRequestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    // init method:  랜덤한 GPX 파일 로드 후 메모리(gpxFile)에 로드
    public void init(String carNumber, String loginId) {
        this.carNumber = carNumber;
        this.loginId = loginId;
        try {

            // classpath 내 /gpx 폴더 경로 가져오기
            var resourceUrl = getClass().getClassLoader().getResource("gpx");
            if (resourceUrl == null) {
                log.error("gpx 폴더를 찾을 수 없습니다.");
                return;
            }

            File dir = new File(resourceUrl.toURI());
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                log.error("gpx 폴더 내에 파일이 없습니다.");
                return;
            }

            // 랜덤 파일 선택
            File randomFile = files[new Random().nextInt(files.length)];
            log.info("선택된 GPX 파일: {}", randomFile.getName());

            // 파일을 한 줄씩 읽어 gpxFile 리스트에 저장 - 위/경도 정보만 필터링
            gpxFile = Files.readAllLines(randomFile.toPath()).stream()
                    .filter(line -> line.trim().startsWith("<trkpt"))
                    .collect(Collectors.toList());

            buffer.clear();

            // random한 시작 위치 지정
            currentIndex = new Random().nextInt(gpxFile.size() - 300);

            // random한 종료 지점 지정 (최소 5분은 주행하도록 보장)
            endIndex = gpxFile.size();

            try{
                startScheduler();
            }catch (Exception e){
                log.info("scheduler가 실행되지 않았습니다.");
            }
        } catch (Exception e) {
            log.error("GPX 파일 로드 중 오류 발생", e);
        }
    }

    // 스케줄러 시작 메서드
    public void startScheduler() {
        Runnable task = () -> {
            try {
                if (carNumber == null) {
                    log.error("********* 차량 정보 없음 **********");
                    return;
                }
                if (currentIndex < endIndex) {
                    // 위도 경도 추출을 위한 정규표현식
                    Pattern pattern = Pattern.compile("lat=\"(.*?)\"\\s+lon=\"(.*?)\"");
                    Matcher matcher = pattern.matcher(gpxFile.get(currentIndex));

                    if (matcher.find()) {
                        // Gpx라인을 Dto로 가공하여 리스트에 삽입
                        timestamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

                        if (finalStartTime == null) { // 애뮬레이터 시동 ON 시점
                            finalStartTime = timestamp; // 시작 시간 지정
                            startLatitude = String.format("%.4f", Double.parseDouble(matcher.group(1)));
                            startLongitude = String.format("%.4f", Double.parseDouble(matcher.group(2)));
                            this.startTime = timestamp; // StartRequestDto에 사용할 시작시간 세팅
                            startDrive();
                        }

                        latitude = String.format("%.4f", Double.parseDouble(matcher.group(1)));
                        longitude = String.format("%.4f", Double.parseDouble(matcher.group(2)));

                        GpxLogDto dto = GpxLogDto.builder()
                                    .timestamp(timestamp)
                                    .latitude(latitude)
                                    .longitude(longitude)
                                    .build();
                        log.info("carNumber: {} latitude: {}, longitude:{} ", carNumber,latitude, longitude);

                        buffer.add(dto);
                    }

                    // 전송 주기가 되면 데이터 전송 함수 실행 - 60초
                    if (currentIndex % 60 == 0 && currentIndex != 0) {
                        sendGpxData();
                    }

                    currentIndex++;
                } else {
                    if (!buffer.isEmpty()) {
                        sendGpxData();  // 버퍼에 남은 데이터 전송
                    }

                    scheduler.shutdown(); // 조건 종료 시 스케줄러 중단
                    log.info("*********** GPX 파일 전송 완료 ***********");
                }
            } catch (Exception e) {
                log.error("GPX 재생 중 오류 발생", e);
            }
        };

        // 종료된 경우 스레드 풀 새로 생성
        if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
        }
        // 일정 간격으로 GPX 데이터 전송 작업 실행
        scheduler.scheduleAtFixedRate(task, 0, 1, TimeUnit.SECONDS);
    }

    // 스케줄러 종료 메서드
    public void stopScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            // 스케줄러 상태 초기화
            log.info("GPX 스케줄러가 중단되었습니다.");
            endDrive();
        } else {
            log.warn("스케줄러가 이미 종료되었거나 시작되지 않았습니다.");
        }
    };

    protected void sendGpxData() {
        startTime = buffer.get(0).getTimestamp();
        endTime = buffer.get(buffer.size() - 1).getTimestamp();

        GpxRequestDto logJson = GpxRequestDto.builder()
                .carNumber(carNumber)
                .loginId(loginId)
                .startTime(startTime)
                .endTime(endTime)
                .logList(buffer)
                .build(); // buffer 내부 로그들 Json화

        // 전송할 Collector API 주소
        String collectorUrl = "http://" + addr + ":8080/api/logs/gps";

        // 테스트를 위한 no rabbit mq url
        //String collectorUrl = "http://52.78.122.150:8080/api/logs/gps-direct";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<GpxRequestDto> request = new HttpEntity<>(logJson, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(collectorUrl, request, String.class);
            log.info("Stop 응답 상태: {}", response.getStatusCode());
            log.info("Stop 응답 바디: {}", response.getBody());
        } catch (HttpStatusCodeException e) {
            log.error("Stop 서버 오류: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Stop API 호출 실패", e);
        }
        buffer.clear();
    }

    private void startDrive() {
        StartRequestDto requestDto = StartRequestDto.builder()
                .carNumber(carNumber)
                .startLatitude(startLatitude)
                .startLongitude(startLongitude)
                .startTime(LocalDateTime.parse(startTime))
                .build();

        String url = "http://" + addr + ":8080/api/drivelogs/start";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<StartRequestDto> request = new HttpEntity<>(requestDto, headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            log.info("startDrive 응답 상태: {}", response.getStatusCode());
            log.info("startDrive 응답 바디: {}", response.getBody());
        } catch (HttpStatusCodeException e) {
            log.error("startDrive 서버 오류: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("startDrive API 호출 실패", e);
        }
    }

    public void endDrive() {
        // 중도 종료 대비: 버퍼의 마지막 포인트로 종료정보 보정
        if (endTime == null) {
            if (!buffer.isEmpty()) {
                GpxLogDto last = buffer.get(buffer.size() - 1);
                endTime = last.getTimestamp();
            } else {
                // 버퍼가 비어있다면 현재 시각으로 보정
                endTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            }
        }
        // 시작 시간이 아직 비어있다면 첫 시점으로 보정
        if (startTime == null && finalStartTime != null) {
            startTime = finalStartTime;
        }
        EndRequestDto requestDto = EndRequestDto.builder()
                .carNumber(carNumber)
                .startTime(LocalDateTime.parse(startTime))
                .endLatitude(latitude)
                .endLongitude(longitude)
                .endTime(LocalDateTime.parse(timestamp))
                .build();

        String url = "http://" + addr + ":8080/api/drivelogs/end";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<EndRequestDto> request = new HttpEntity<>(requestDto, headers);
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PATCH, request, String.class);
            log.info("endDrive 응답 상태: {}", response.getStatusCode());
            log.info("endDrive 응답 바디: {}", response.getBody());
        } catch (HttpStatusCodeException e) {
            log.error("endDrive 서버 오류: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("endDrive API 호출 실패", e);
        }
    }
}
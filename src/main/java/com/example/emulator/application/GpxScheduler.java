package com.example.emulator.application;

import com.example.emulator.application.dto.EndRequestDto;
import com.example.emulator.application.dto.GpxLogDto;
import com.example.emulator.application.dto.GpxRequestDto;
import com.example.emulator.application.dto.StartRequestDto;
import com.example.emulator.car.CarReader;
import com.example.emulator.car.CarStatus;
import com.example.emulator.infrastructure.car.CarRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class GpxScheduler {

    private final CarReader carReader;
    private final CarRepository carRepository;
    private final RestTemplate restTemplate;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);
    private final Map<String, ScheduledFuture<?>> runningTasks = new ConcurrentHashMap<>();

    @Value("${app.gpx.pattern:classpath*:gpx/*.gpx}")
    private String gpxPattern;

    private List<GpxLogDto> buffer; // Gpx buffer

    String timestamp = null;
    String latitude = null;
    String longitude = null;
    String startTime = null;


    public void startGpxSimulation(String carNumber, String loginId) {

        try {
            log.info("Starting GPX simulation for car: {}", carNumber);
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(gpxPattern);

            if (resources.length == 0) {
                log.error("GPX resources not found with pattern: {}", gpxPattern);
                return;
            }

            Resource randomRes = resources[ThreadLocalRandom.current().nextInt(resources.length)];
            log.info("Selected GPX file for car {}: {}", carNumber, randomRes.getFilename());

            List<String> gpxFileLines;
            try (var in = randomRes.getInputStream();
                 var br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                gpxFileLines = br.lines()
                        .filter(line -> line.contains("<trkpt"))
                        .collect(Collectors.toList());
            }

            if (gpxFileLines.isEmpty()) {
                log.error("No <trkpt> lines found in the selected GPX file.");
                return;
            }

            Runnable simulationTask = createSimulationTask(carNumber, loginId, gpxFileLines);
            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(simulationTask, 0, 1, TimeUnit.SECONDS);
            runningTasks.put(carNumber, future);

        } catch (Exception e) {
            log.error("Error starting GPX simulation for car: " + carNumber, e);
        }
    }

    public void stopGpxSimulation(String carNumber, String loginId) {
        ScheduledFuture<?> future = runningTasks.remove(carNumber);
        if (future != null) {
            future.cancel(true);
            log.info("Stopped GPX simulation for car: {}", carNumber);
        }
        // 버퍼에 미전송 데이터 있으면 전송
        if (buffer != null && !buffer.isEmpty()) {
            log.info("차량: {}, 종료 시 잔여 데이터 전송", carNumber);
            sendGpxData(carNumber, loginId, new ArrayList<>(buffer)); // loginId 필요하다면 파라미터 추가

            GpxLogDto lastLog = buffer.get(buffer.size() - 1);
            this.latitude = lastLog.getLatitude();
            this.longitude = lastLog.getLongitude();
            this.timestamp = lastLog.getTimestamp();

            buffer.clear();
        }

        endDrive(carNumber);
    }

    private Runnable createSimulationTask(String carNumber, String loginId, List<String> gpxFileLines) {
        buffer = new ArrayList<>();
        int totalPoints = gpxFileLines.size();
        int window = Math.min(300, totalPoints);
        int maxStart = Math.max(0, totalPoints - window);

        // 시작 인덱스를 저장해서 경과 시간을 계산하는 데 사용
        final int startIndex = (maxStart == 0) ? 0 : ThreadLocalRandom.current().nextInt(maxStart + 1);
        final int[] currentIndex = { startIndex };
        final int endIndex = Math.min(totalPoints, startIndex + window);

        return () -> {
            try {
                // 매 초마다 현재 진행 시간 로그 출력
                long elapsedTime = currentIndex[0] - startIndex;
                log.info("차량: {}, 시뮬레이션 진행 시간: {}초", carNumber, elapsedTime);

                if (currentIndex[0] < endIndex) {
                    Pattern pattern = Pattern.compile("lat=\"(.*?)\"\\s+lon=\"(.*?)\"");
                    Matcher matcher = pattern.matcher(gpxFileLines.get(currentIndex[0]));
                    if (matcher.find()) {
                        timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
                        latitude = String.format("%.4f", Double.parseDouble(matcher.group(1)));
                        longitude = String.format("%.4f", Double.parseDouble(matcher.group(2)));
                        buffer.add(GpxLogDto.builder().timestamp(timestamp).latitude(latitude).longitude(longitude).build());

                        if(startTime == null) {
                            startTime = timestamp;
                            startDrive(carNumber, latitude, longitude);
                        }
                    }

                    // 60초마다 데이터 전송 (시작 후 60초, 120초...)
                    if (elapsedTime > 0 && elapsedTime % 60 == 0) {
                        log.info("차량: {}, 60초 경과, 데이터 전송", carNumber);
                        sendGpxData(carNumber, loginId, new ArrayList<>(buffer));
                        buffer.clear();
                    }
                    currentIndex[0]++;
                } else {
                    if (!buffer.isEmpty()) {
                        sendGpxData(carNumber, loginId, new ArrayList<>(buffer));
                    }
                    log.info("GPX simulation finished for car: {}", carNumber);
                    carReader.findByCarNumber(carNumber).ifPresent(entity -> {
                        entity.setStatus(CarStatus.IDLE);
                        carRepository.save(entity);
                    });
                    stopGpxSimulation(carNumber, loginId);
                }
            } catch (Exception e) {
                log.error("Error during GPX simulation for car: " + carNumber, e);
                stopGpxSimulation(carNumber, loginId);
            }
        };
    }

    private void sendGpxData(String carNumber, String loginId, List<GpxLogDto> payload) {
        if (payload.isEmpty()) return;

        String startTime = payload.get(0).getTimestamp();
        String endTime = payload.get(payload.size() - 1).getTimestamp();

        GpxRequestDto logJson = GpxRequestDto.builder()
                .carNumber(carNumber)
                .loginId(loginId)
                .startTime(startTime)
                .endTime(endTime)
                .logList(payload)
                .build();

        log.info("Sending GPX log for car {}: {}", carNumber, logJson);
        String collectorUrl = "http://52.78.122.150:8080/api/logs/gps";
//        String collectorUrl = "https://localhost:8080/api/logs/gps";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<GpxRequestDto> request = new HttpEntity<>(logJson, headers);

        try {
            restTemplate.postForEntity(collectorUrl, request, String.class);
        } catch (Exception e) {
            log.error("Failed to call collector API for car: " + carNumber, e);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down GpxScheduler's executor service.");
        scheduler.shutdownNow();
    }

    private void startDrive(String carNumber, String startLatitude, String startLongitude) {
        StartRequestDto requestDto = StartRequestDto.builder()
                .carNumber(carNumber)
                .startLatitude(startLatitude)
                .startLongitude(startLongitude)
                .startTime(LocalDateTime.parse(startTime))
                .build();

        String url = "http://52.78.122.150:8080/api/drivelogs/start";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<StartRequestDto> request = new HttpEntity<>(requestDto, headers);
        log.info("[JWT] request url={}", url);
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

    public void endDrive(String carNumber) {
        EndRequestDto requestDto = EndRequestDto.builder()
                .carNumber(carNumber)
                .startTime(LocalDateTime.parse(startTime))
                .endLatitude(latitude)
                .endLongitude(longitude)
                .endTime(LocalDateTime.parse(timestamp))
                .build();

        startTime = null;

        String url = "http://52.78.122.150:8080/api/drivelogs/end";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<EndRequestDto> request = new HttpEntity<>(requestDto, headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            log.info("endDrive 응답 상태: {}", response.getStatusCode());
            log.info("endDrive 응답 바디: {}", response.getBody());
        } catch (HttpStatusCodeException e) {
            log.error("endDrive 서버 오류: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("endDrive API 호출 실패", e);
        }
    }
}

//package com.example.emulator.application;
//
//import com.example.emulator.application.dto.GpxLogDto;
//import com.example.emulator.application.dto.GpxRequestDto;
//import com.example.emulator.application.dto.StartRequestDto;
//import com.example.emulator.car.CarReader;
//import com.example.emulator.car.CarStatus;
//import com.example.emulator.infrastructure.car.CarRepository;
//
//import jakarta.annotation.PreDestroy;
//import lombok.RequiredArgsConstructor;
//import lombok.Getter;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.http.HttpEntity;
//import org.springframework.http.HttpHeaders;
//import org.springframework.http.MediaType;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.client.HttpStatusCodeException;
//import org.springframework.web.client.RestTemplate;
//
//import java.io.File;
//import java.nio.file.Files;
//import java.time.LocalDateTime;
//import java.time.format.DateTimeFormatter;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Random;
//import java.util.concurrent.Executors;
//import java.util.concurrent.ScheduledExecutorService;
//import java.util.concurrent.TimeUnit;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//import java.util.stream.Collectors;
//
//@Getter
//@Slf4j
//public class GpxScheduler{
//    private final RestTemplate restTemplate; // api 호출을 위함
//
//    private List<String> gpxFile = new ArrayList<>(); // Gpx 파일을 읽어와 저장해두는 리스트
//    private List<GpxLogDto> buffer = new ArrayList<>(); // 전송될 GPX 정보들을 저장해두는 리스트
//    private int currentIndex = 0; // 읽어야 할 line 번호
//    private int endIndex = 0; // 해당 인덱스까지 읽기
//
//    private String carNumber;
//    private String loginId;
//    private String startTime;
//    private String endTime;
//
//    // 스케줄러 실행 여부 확인
//    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
//
//    public GpxScheduler(RestTemplate restTemplate) {
//        this.restTemplate = restTemplate;
//    }
//
//    // init method:  랜덤한 GPX 파일 로드 후 메모리(gpxFile)에 로드
//    public void init(String carNumber, String loginId) {
//        this.carNumber = carNumber;
//        this.loginId = loginId;
//        try {
//
//            // classpath 내 /gpx 폴더 경로 가져오기
//            var resourceUrl = getClass().getClassLoader().getResource("gpx");
//            if (resourceUrl == null) {
//                log.error("gpx 폴더를 찾을 수 없습니다.");
//                return;
//            }
//
//            File dir = new File(resourceUrl.toURI());
//            File[] files = dir.listFiles();
//            if (files == null || files.length == 0) {
//                log.error("gpx 폴더 내에 파일이 없습니다.");
//                return;
//            }
//
//            // 랜덤 파일 선택
//            File randomFile = files[new Random().nextInt(files.length)];
//            log.info("선택된 GPX 파일: {}", randomFile.getName());
//
//            // 파일을 한 줄씩 읽어 gpxFile 리스트에 저장 - 위/경도 정보만 필터링
//            gpxFile = Files.readAllLines(randomFile.toPath()).stream()
//                    .filter(line -> line.trim().startsWith("<trkpt"))
//                    .collect(Collectors.toList());
//
//            buffer.clear();
//
//            // random한 시작 위치 지정
//            currentIndex = new Random().nextInt((int)(gpxFile.size() - 300));
//            // random한 종료 지점 지정 (최소 5분은 주행하도록 보장)
//            endIndex = new Random().nextInt(currentIndex + 300, gpxFile.size());
//
//            try{
//                startScheduler();
//            }catch (Exception e){
//                log.info("scheduler가 실행되지 않았습니다.");
//            }
//        } catch (Exception e) {
//            log.error("GPX 파일 로드 중 오류 발생", e);
//        }
//    }
//
//    // 스케줄러 시작 메서드
//    public void startScheduler() {
//        Runnable task = () -> {
//            try {
//                if (carNumber == null) {
//                    log.error("********* 차량 정보 없음 **********");
//                    return;
//                }
//                if (currentIndex < endIndex) {
//                    // 위도 경도 추출을 위한 정규표현식
//                    Pattern pattern = Pattern.compile("lat=\"(.*?)\"\\s+lon=\"(.*?)\"");
//                    Matcher matcher = pattern.matcher(gpxFile.get(currentIndex));
//
//                    if (matcher.find()) {
//                        // Gpx라인을 Dto로 가공하여 리스트에 삽입
//                        String timestamp = LocalDateTime.now()
//                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
//
//                        String latitude = String.format("%.4f", Double.parseDouble(matcher.group(1)));
//                        String longitude = String.format("%.4f", Double.parseDouble(matcher.group(2)));
//
//                        GpxLogDto dto = GpxLogDto.builder()
//                                .timestamp(timestamp)
//                                .latitude(latitude)
//                                .longitude(longitude)
//                                .build();
//                        log.info("carNumber: {} latitude: {}, longitude:{} ", carNumber,latitude, longitude);
//
//                        buffer.add(dto);
//                    }
//
//                    // 전송 주기가 되면 데이터 전송 함수 실행 - 60초
//                    if (currentIndex % 60 == 0 && currentIndex != 0) {
//                        sendGpxData();
//                    }
//
//                    currentIndex++;
//                } else {
//                    if (!buffer.isEmpty()) {
//                        sendGpxData();  // 버퍼에 남은 데이터 전송
//                    }
//
//                    scheduler.shutdown(); // 조건 종료 시 스케줄러 중단
//                    log.info("*********** GPX 파일 전송 완료 ***********");
//                }
//            } catch (Exception e) {
//                log.error("GPX 재생 중 오류 발생", e);
//            }
//        };
//
//        // 종료된 경우 스레드 풀 새로 생성
//        if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) {
//            scheduler = Executors.newSingleThreadScheduledExecutor();
//        }
//        // 일정 간격으로 GPX 데이터 전송 작업 실행
//        scheduler.scheduleAtFixedRate(task, 0, 1, TimeUnit.SECONDS);
//    }
//
//    // 스케줄러 종료 메서드
//    public void stopScheduler() {
//        if (scheduler != null && !scheduler.isShutdown()) {
//            scheduler.shutdownNow();
//            // 스케줄러 상태 초기화
//            log.info("GPX 스케줄러가 중단되었습니다.");
//        } else {
//            log.warn("스케줄러가 이미 종료되었거나 시작되지 않았습니다.");
//        }
//    };
//
//    protected void sendGpxData() {
//        startTime = buffer.get(0).getTimestamp();
//        endTime = buffer.get(buffer.size() - 1).getTimestamp();
//
//
//
//        GpxRequestDto logJson = GpxRequestDto.builder()
//                .carNumber(carNumber)
//                .loginId(loginId)
//                .startTime(startTime)
//                .endTime(endTime)
//                .logList(buffer)
//                .build(); // buffer 내부 로그들 Json화
//
//        // 전송할 Collector API 주소
//        String collectorUrl = "http://52.78.122.150:8080/api/logs/gps";
//
//        // 테스트를 위한 no rabbit mq url
//        //String collectorUrl = "http://52.78.122.150:8080/api/logs/gps-direct";
//
//        HttpHeaders headers = new HttpHeaders();
//        headers.setContentType(MediaType.APPLICATION_JSON);
//
//        HttpEntity<GpxRequestDto> request = new HttpEntity<>(logJson, headers);
//
//        try {
//            ResponseEntity<String> response = restTemplate.postForEntity(collectorUrl, request, String.class);
//            log.info("Collector 응답 상태: {}", response.getStatusCode());
//            log.info("Collector 응답 바디: {}", response.getBody());
//        } catch (HttpStatusCodeException e) {
//            log.error("Collector 서버 오류: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
//        } catch (Exception e) {
//            log.error("Collector API 호출 실패", e);
//        }
//        buffer.clear();
//    }
//}
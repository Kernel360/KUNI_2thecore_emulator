package com.example.emulator.application;

import com.example.emulator.application.dto.GpxLogDto;
import com.example.emulator.application.dto.GpxRequestDto;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;                    // ★추가
import org.springframework.core.io.Resource;                           // ★추가
import org.springframework.core.io.support.PathMatchingResourcePatternResolver; // ★추가
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;                                         // ★추가
import java.io.InputStreamReader;                                      // ★추가
import java.nio.charset.StandardCharsets;                               // ★추가
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;                         // ★추가
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Setter
@Getter
@Slf4j
public class GpxScheduler {

    @Autowired
    private RestTemplate restTemplate;

    private List<String> gpxFile = new ArrayList<>();
    private List<GpxLogDto> buffer = new ArrayList<>();
    private int currentIndex = 0;
    private int endIndex = 0;

    private String carNumber;
    private String loginId;
    private String startTime;
    private String endTime;

    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // ★추가: 환경별로 GPX 위치를 바꾸고 싶을 때 사용 (기본은 classpath)
    @Value("${app.gpx.pattern:classpath*:gpx/*.gpx}")
    private String gpxPattern;

    // ★변경: JAR에서도 동작하도록 리소스 패턴으로 GPX 파일 검색
    public void init() {
        try {
            log.info("init gpx scheduler (pattern={})", gpxPattern);

            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(gpxPattern);

            if (resources.length == 0) {
                log.error("GPX 리소스를 찾을 수 없습니다. 패턴={}", gpxPattern);
                return;
            }

            Resource randomRes = resources[ThreadLocalRandom.current().nextInt(resources.length)];
            log.info("선택된 GPX 파일: {}", randomRes.getFilename());

            try (var in = randomRes.getInputStream();
                 var br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

                gpxFile = br.lines()
                        .filter(line -> line.contains("<trkpt"))
                        .collect(Collectors.toList());
            }

            if (gpxFile.isEmpty()) {
                log.error("선택된 GPX에서 <trkpt> 라인을 찾지 못했습니다.");
                return;
            }

            // ★안전화: 포인트가 적어도 동작하도록 윈도우/인덱스 계산
            int window = Math.min(300, gpxFile.size()); // 최대 300포인트 사용
            int maxStart = Math.max(0, gpxFile.size() - window);
            currentIndex = (maxStart == 0) ? 0 : ThreadLocalRandom.current().nextInt(maxStart + 1);
            endIndex = Math.min(gpxFile.size(), currentIndex + window);

            buffer.clear();
            startScheduler();

            log.info("스케줄러 시작: points={}, start={}, end={}", gpxFile.size(), currentIndex, endIndex);

        } catch (Exception e) {
            log.error("GPX 파일 로드 중 오류 발생", e);
        }
    }

    public void startScheduler() {
        Runnable task = () -> {
            try {
                if (carNumber == null) {
                    log.error("********* 차량 정보 없음 **********");
                    return;
                }
                if (currentIndex < endIndex) {
                    Pattern pattern = Pattern.compile("lat=\"(.*?)\"\\s+lon=\"(.*?)\"");
                    Matcher matcher = pattern.matcher(gpxFile.get(currentIndex));
                    if (matcher.find()) {
                        String timestamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

                        String latitude = String.format("%.4f", Double.parseDouble(matcher.group(1)));
                        String longitude = String.format("%.4f", Double.parseDouble(matcher.group(2)));

                        GpxLogDto dto = GpxLogDto.builder()
                                .timestamp(timestamp)
                                .latitude(latitude)
                                .longitude(longitude)
                                .build();

                        buffer.add(dto);
                    }

                    if (currentIndex % 60 == 0 && currentIndex != 0) { // 60초마다 전송
                        sendGpxData();
                    }

                    currentIndex++;
                } else {
                    if (!buffer.isEmpty()) {
                        sendGpxData();
                    }
                    scheduler.shutdown();
                    log.info("*********** GPX 파일 전송 완료 ***********");
                }
            } catch (Exception e) {
                log.error("GPX 재생 중 오류 발생", e);
            }
        };

        if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
        }
        scheduler.scheduleAtFixedRate(task, 0, 1, TimeUnit.SECONDS);
    }

    public void stopScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            log.info("GPX 스케줄러가 중단되었습니다.");
        } else {
            log.warn("스케줄러가 이미 종료되었거나 시작되지 않았습니다.");
        }
    }

    protected void sendGpxData() {
        // ★추가: 빈 버퍼 가드 (NPE 방지)
        if (buffer.isEmpty()) return;

        startTime = buffer.get(0).getTimestamp();
        endTime   = buffer.get(buffer.size() - 1).getTimestamp();

        // 전송할 payload는 복사본 사용(동시성/클리어 전 안전)
        List<GpxLogDto> payload = new ArrayList<>(buffer);

        GpxRequestDto logJson = GpxRequestDto.builder()
                .carNumber(carNumber)
                .loginId(loginId)
                .startTime(startTime)
                .endTime(endTime)
                .logList(payload)
                .build();

        log.info("gpsLog : {}", logJson);

        String collectorUrl = "http://52.78.122.150:8080/api/logs/gps";
//        String collectorUrl = "http://localhost:8080/api/logs/gps";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<GpxRequestDto> request = new HttpEntity<>(logJson, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(collectorUrl, request, String.class);
            log.info("Collector 응답 상태: {}", response.getStatusCode());
            log.info("Collector 응답 바디: {}", response.getBody());
        } catch (HttpStatusCodeException e) {
            log.error("Collector 서버 오류: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Collector API 호출 실패", e);
        }

        buffer.clear();
    }
}

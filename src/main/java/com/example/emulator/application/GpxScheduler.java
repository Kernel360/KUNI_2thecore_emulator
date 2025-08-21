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
import org.springframework.http.*;
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

    List<GpxLogDto> buffer;

    String timestamp = null;
    String latitude = null;
    String longitude = null;

    private String startTime = null;


    public void startGpxSimulation(String carNumber, String loginId) {
        stopGpxSimulation(carNumber, loginId);

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

            // 버퍼에 미전송 데이터 있으면 전송
            if (buffer != null && !buffer.isEmpty()) {
                log.info("차량: {}, 종료 시 잔여 데이터 전송", carNumber);
                sendGpxData(carNumber, loginId, new ArrayList<>(buffer)); // loginId 필요하다면 파라미터 추가
                buffer.clear();
            }

            endDrive(carNumber);
        }
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

                        if (startTime == null){ // start 정보 변수에 저장 및 startDrive 실행
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
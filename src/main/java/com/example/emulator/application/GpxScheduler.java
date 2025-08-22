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
import java.time.ZoneId;
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

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(64);
    private final Map<String, ScheduledFuture<?>> runningTasks = new ConcurrentHashMap<>();

    // 차량별 상태 컨텍스트
    private static class TaskCtx {
        final List<GpxLogDto> buf = new ArrayList<>();
        List<String> lines;
        int startIndex;
        int endIndex;

        String startTime; // ISO-Local "yyyy-MM-dd'T'HH:mm:ss"
        String lastTs;
        String lastLat;
        String lastLon;
    }
    private final Map<String, TaskCtx> contexts = new ConcurrentHashMap<>();

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Value("${app.gpx.pattern:classpath*:gpx/*.gpx}")
    private String gpxPattern;

    public void startGpxSimulation(String carNumber, String loginId) {
        // 같은 차량이 이미 돌고 있으면 정리
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

            // 컨텍스트 준비
            TaskCtx ctx = new TaskCtx();
            ctx.lines = gpxFileLines;

            int totalPoints = gpxFileLines.size();
            int window = Math.min(300, totalPoints);
            int maxStart = Math.max(0, totalPoints - window);
            ctx.startIndex = (maxStart == 0) ? 0 : ThreadLocalRandom.current().nextInt(maxStart + 1);
            ctx.endIndex = Math.min(totalPoints, ctx.startIndex + window);

            contexts.put(carNumber, ctx);

            Runnable simulationTask = createSimulationTask(carNumber, loginId, ctx);
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

        TaskCtx ctx = contexts.remove(carNumber);
        if (ctx != null) {
            // 잔여 버퍼 전송
            if (!ctx.buf.isEmpty()) {
                log.info("차량: {}, 종료 시 잔여 데이터 전송 {}", carNumber, ctx.buf.size());
                sendGpxData(carNumber, loginId, new ArrayList<>(ctx.buf));
                ctx.buf.clear();
            }
            // 종료 API 호출
            endDrive(carNumber, ctx);
        }
    }

    private Runnable createSimulationTask(String carNumber, String loginId, TaskCtx ctx) {
        final Pattern pattern = Pattern.compile("lat=\"(.*?)\"\\s+lon=\"(.*?)\"");
        final int[] currentIndex = { ctx.startIndex };

        return () -> {
            try {
                long elapsedTime = currentIndex[0] - ctx.startIndex;
                log.info("차량: {}, 시뮬레이션 진행 시간: {}초", carNumber, elapsedTime);

                if (currentIndex[0] < ctx.endIndex) {
                    Matcher matcher = pattern.matcher(ctx.lines.get(currentIndex[0]));
                    if (matcher.find()) {
                        String now = LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(TS_FMT);
                        String lat = String.format("%.4f", Double.parseDouble(matcher.group(1)));
                        String lon = String.format("%.4f", Double.parseDouble(matcher.group(2)));

                        if (ctx.startTime == null) {
                            ctx.startTime = now;
                            startDrive(carNumber, lat, lon, ctx.startTime);
                        }

                        ctx.lastTs = now;
                        ctx.lastLat = lat;
                        ctx.lastLon = lon;

                        ctx.buf.add(GpxLogDto.builder().timestamp(now).latitude(lat).longitude(lon).build());

                        // ★ 버퍼 크기 기준으로 60개씩 전송
                        while (ctx.buf.size() >= 60) {
                            List<GpxLogDto> batch = new ArrayList<>(ctx.buf.subList(0, 60));
                            ctx.buf.subList(0, 60).clear();
                            log.info("car={} send batch size={}", carNumber, batch.size());
                            sendGpxData(carNumber, loginId, batch);
                        }
                    }
                    currentIndex[0]++;

                } else {
                    // 종료 시 잔여 전송 (60 미만은 정상)
                    if (!ctx.buf.isEmpty()) {
                        log.info("car={} final flush size={}", carNumber, ctx.buf.size());
                        sendGpxData(carNumber, loginId, new ArrayList<>(ctx.buf));
                        ctx.buf.clear();
                    }

                    log.info("GPX simulation finished for car: {}", carNumber);
                    carReader.findByCarNumber(carNumber).ifPresent(entity -> {
                        entity.setStatus(CarStatus.IDLE);
                        carRepository.save(entity);
                    });
                    // stop은 future cancel + endDrive + flush 담당
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

        log.info("Sending GPX log for car {}: count={}", carNumber, payload.size());
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

    private void startDrive(String carNumber, String startLatitude, String startLongitude, String startTimeIso) {
        StartRequestDto requestDto = StartRequestDto.builder()
                .carNumber(carNumber)
                .startLatitude(startLatitude)
                .startLongitude(startLongitude)
                .startTime(LocalDateTime.parse(startTimeIso))
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

    private void endDrive(String carNumber, TaskCtx ctx) {
        if (ctx.startTime == null || ctx.lastTs == null) return;

        EndRequestDto requestDto = EndRequestDto.builder()
                .carNumber(carNumber)
                .startTime(LocalDateTime.parse(ctx.startTime))
                .endLatitude(ctx.lastLat)
                .endLongitude(ctx.lastLon)
                .endTime(LocalDateTime.parse(ctx.lastTs))
                .build();

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

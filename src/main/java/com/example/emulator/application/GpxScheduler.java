package com.example.emulator.application;

import com.example.emulator.application.dto.GpxLogDto;
import com.example.emulator.application.dto.GpxRequestDto;
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
import org.springframework.stereotype.Component;
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

    public void startGpxSimulation(String carNumber, String loginId) {
        stopGpxSimulation(carNumber);

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

    public void stopGpxSimulation(String carNumber) {
        ScheduledFuture<?> future = runningTasks.remove(carNumber);
        if (future != null) {
            future.cancel(true);
            log.info("Stopped GPX simulation for car: {}", carNumber);
        }
    }

    private Runnable createSimulationTask(String carNumber, String loginId, List<String> gpxFileLines) {
        List<GpxLogDto> buffer = new ArrayList<>();
        int totalPoints = gpxFileLines.size();
        int window = Math.min(300, totalPoints);
        int maxStart = Math.max(0, totalPoints - window);
        final int[] currentIndex = { (maxStart == 0) ? 0 : ThreadLocalRandom.current().nextInt(maxStart + 1) };
        int endIndex = Math.min(totalPoints, currentIndex[0] + window);

        return () -> {
            try {
                if (currentIndex[0] < endIndex) {
                    Pattern pattern = Pattern.compile("lat=\"(.*?)\"\\s+lon=\"(.*?)\"");
                    Matcher matcher = pattern.matcher(gpxFileLines.get(currentIndex[0]));
                    if (matcher.find()) {
                        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
                        String latitude = String.format("%.4f", Double.parseDouble(matcher.group(1)));
                        String longitude = String.format("%.4f", Double.parseDouble(matcher.group(2)));
                        buffer.add(GpxLogDto.builder().timestamp(timestamp).latitude(latitude).longitude(longitude).build());
                    }

                    if (currentIndex[0] > 0 && currentIndex[0] % 60 == 0) {
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
                    stopGpxSimulation(carNumber);
                }
            } catch (Exception e) {
                log.error("Error during GPX simulation for car: " + carNumber, e);
                stopGpxSimulation(carNumber);
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
}
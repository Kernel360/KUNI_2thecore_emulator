package com.example.emulator.application.exception;

import com.example.emulator.application.dto.GpxLogDto;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GpxExceptionHandler {

    private GpxLogDto previousGpxLog = null;
    private int freezeCount = 0;

    private static final int FREEZE_THRESHOLD = 10; // 10초(라인) 이상 동일 좌표일 경우 프리즈로 간주
    private static final double SPIKE_DISTANCE_THRESHOLD_METER = 140; // 1초당 140m 이동(약 500km/h)을 스파이크로 간주

    /**
     * 현재 좌표가 이전 좌표와 비교하여 '위치 스파이크'에 해당하는지 확인
     * @param currentGpxLog 현재 GPX 데이터 포인트
     * @return 스파이크인 경우 true
     */
    public boolean isSpike(GpxLogDto currentGpxLog) {
        if (previousGpxLog == null) {
            return false;
        }

        double distance = calculateDistance(
                Double.parseDouble(previousGpxLog.getLatitude()),
                Double.parseDouble(previousGpxLog.getLongitude()),
                Double.parseDouble(currentGpxLog.getLatitude()),
                Double.parseDouble(currentGpxLog.getLongitude())
        );

        if (distance > SPIKE_DISTANCE_THRESHOLD_METER) {
            log.error("[Location Spike Detected] distance: {}m", String.format("%.2f", distance));
            return true;
        }
        return false;
    }

    /**
     * 현재 좌표가 이전 좌표와 비교하여 '신호 끊김(프리즈)'에 해당하는지 확인
     * @param currentGpxLog 현재 GPX 데이터 포인트
     * @return 프리즈 상태인 경우 true
     */
    public boolean isFreeze(GpxLogDto currentGpxLog) {
        if (previousGpxLog != null &&
                previousGpxLog.getLatitude().equals(currentGpxLog.getLatitude()) &&
                previousGpxLog.getLongitude().equals(currentGpxLog.getLongitude())) {
            freezeCount++;
        } else {
            // 좌표가 변경되면 카운터 리셋
            freezeCount = 0;
        }

        if (freezeCount >= FREEZE_THRESHOLD) {
            log.warn("[Signal Freeze Detected] freezeCount: {}", freezeCount);
            return true;
        }
        return false;
    }

    /**
     * 유효한 현재 포인트를 다음 비교를 위해 이전 포인트로 업데이트
     * @param currentGpxLog 유효하다가 판단된 현재 GPX 데이터 포인트
     */
    public void updatePreviousPoint(GpxLogDto currentGpxLog) {
        this.previousGpxLog = currentGpxLog;
    }

    /**
     * 새로운 GPX 파일 처리를 위해 상태를 초기화
     */
    public void reset() {
        this.previousGpxLog = null;
        this.freezeCount = 0;
    }

    /**
     * 두 위도/경도 좌표 간의 거리를 미터(m) 단위로 계산
     */
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6371000; // meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadius * c;
    }
}

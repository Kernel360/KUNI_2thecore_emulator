package com.example.emulator.application;

import com.example.emulator.car.CarStatus;
import com.example.emulator.car.domain.CarEntity;
import com.example.emulator.infrastructure.car.CarRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class updateCarStatusService {

    private final CarRepository carRepository;

    @Async("dbExecutor")
    @Transactional
    @Retryable(
            // 🚨 낙관적 락킹 실패 예외 발생 시 재시도
            value = { OptimisticLockingFailureException.class, ObjectOptimisticLockingFailureException.class },
            maxAttempts = 5,        // ⬅️ 최대 5번 시도
            backoff = @Backoff(delay = 100) // ⬅️ 100ms 대기 후 재시도
    )
    public void updateCarStatusAsync(String carNumber, CarStatus carStatus){
        try{
            CarEntity car = carRepository.findByCarNumber(carNumber)
                    .orElseThrow(() -> new RuntimeException("car not found"));
            car.setStatus(carStatus);
            carRepository.save(car);
            log.info("비동기로 차량 상태 변경 : {} -> {}", carNumber, carStatus);
        }catch(OptimisticLockingFailureException e) {
            // 🚨 충돌이므로 예외를 다시 던져서 @Retryable이 재시도 로직을 발동시키도록 합니다.
            log.warn("낙관적 락 충돌 발생 (재시도 진행 중): {}", carNumber);
            throw e;
        }
    }

}

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
            value = { OptimisticLockingFailureException.class, ObjectOptimisticLockingFailureException.class },
            maxAttempts = 5,
            backoff = @Backoff(delay = 100)
    )
    public void updateCarStatusAsync(String carNumber, CarStatus carStatus){
        try{
            CarEntity car = carRepository.findByCarNumber(carNumber)
                    .orElseThrow(() -> new RuntimeException("car not found"));
            car.setStatus(carStatus);
            carRepository.save(car);
            log.info("비동기로 차량 상태 변경 : {} -> {}", carNumber, carStatus);
        }catch(OptimisticLockingFailureException e) {
            log.warn("락 충돌 발생 재시도: {}", carNumber);
            throw e;
        }
    }

}

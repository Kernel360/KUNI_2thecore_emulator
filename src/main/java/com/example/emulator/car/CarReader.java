package com.example.emulator.car;

import com.example.emulator.car.domain.CarEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface CarReader  {
    Optional<CarEntity> findByCarNumber(String carNumber);

    Map<CarStatus, Long> getCountByStatus();

    Optional<CarEntity> findByEmulatorId(Integer emulatorId);

    List<CarEntity> findByStatus(List<CarStatus> statuses);

}

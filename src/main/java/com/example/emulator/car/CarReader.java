package com.example.emulator.car;

import com.example.emulator.car.domain.CarEntity;

import java.util.List;
import java.util.Optional;

public interface CarReader  {

    Optional<CarEntity> findByCarNumber(String carNumber);

    List<CarEntity> findAll(String loginId);
}

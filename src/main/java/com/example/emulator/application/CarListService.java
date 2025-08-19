package com.example.emulator.application;

import com.example.emulator.car.domain.CarEntity;
import com.example.emulator.infrastructure.car.CarRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CarListService {

    private final CarRepository carRepository;

    public List<CarEntity> getCarList(String loginId) {
        return carRepository.findAllByLoginId(loginId);
    }
}

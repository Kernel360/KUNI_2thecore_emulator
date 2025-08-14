package com.example.emulator.infrastructure.car;

import com.example.emulator.car.domain.CarEntity;
import com.example.emulator.car.CarReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "car.reader.db.enabled", havingValue = "true", matchIfMissing = true)

public class CarReaderImpl implements CarReader {

    private final CarRepository carRepository;

    public CarReaderImpl(CarRepository carRepository) {
        this.carRepository = carRepository;
    }

    public Optional<CarEntity> findByCarNumber(String carNumber){
        return carRepository.findByCarNumber(carNumber);
    }

    public List<CarEntity> findAll(String loginId){
        return carRepository.findAllByLoginId(loginId);
    }
}
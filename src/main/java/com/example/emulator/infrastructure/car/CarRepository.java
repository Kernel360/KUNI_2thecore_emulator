package com.example.emulator.infrastructure.car;

import com.example.emulator.car.domain.CarEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository

public interface CarRepository extends JpaRepository<CarEntity, Integer> {

    Optional<CarEntity> findByCarNumber(String carNumber);

    List<CarEntity> findAllByLoginId(String loginId);
}

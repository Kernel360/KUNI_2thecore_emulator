package com.example.emulator.application;

import com.example.emulator.application.dto.DriveLogEventDto;
import com.example.emulator.car.CarStatus;
import com.example.emulator.car.domain.CarEntity;
import com.example.emulator.car.exception.CarErrorCode;
import com.example.emulator.car.exception.CarNotFoundException;
import com.example.emulator.car.CarReader;
import com.example.emulator.controller.dto.LogPowerDto;
import com.example.emulator.infrastructure.car.CarRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;


@Slf4j
@Service
@RequiredArgsConstructor
public class LogService {

    private final CarReader carReader;
    private final CarRepository carRepository;
    private final RestTemplate restTemplate;

    private final RabbitMqPublisher rabbitMqPublisher;
    private final updateCarStatusService updateCarStatusService;

    private static final String DRIVE_LOG_EXCHANGE = "drive.log.exchange";

    @Qualifier("gpxSchedulerPool")
    private final ScheduledExecutorService gpxSchedulerPool;


    private final Map<String, GpxScheduler> schedulers = new ConcurrentHashMap<>();

    public LogPowerDto changePowerStatus(LogPowerDto logPowerDto) {
        String carNumber = logPowerDto.getCarNumber();
        String loginId = logPowerDto.getLoginId();
        String powerStatus = logPowerDto.getPowerStatus();

        CarEntity carEntity = carReader.findByCarNumber(carNumber)
                .orElseThrow(() -> new CarNotFoundException(CarErrorCode.CAR_NOT_FOUND_BY_NUMBER, carNumber));

        if (powerStatus.equals("ON")) {
            // 이미 실행 중인 스케줄러가 있으면 중지
            if (schedulers.containsKey(carNumber)) {
                log.info("이미 실행중인 스케줄러가 있어, 기존 스케줄러를 중지합니다: {}", carNumber);
                schedulers.get(carNumber).stopScheduler();
                schedulers.remove(carNumber);
            }

            //status 변경 "운행"
            log.info("차량 상태 ON: {} ", carNumber);

            updateCarStatusService.updateCarStatusAsync(carNumber, CarStatus.DRIVING);

            carEntity.setStatus(CarStatus.IDLE);


            DriveLogEventDto driveLogOnEvent = DriveLogEventDto.builder()
                    .eventTime(LocalDateTime.now())
                    .carNumber(carEntity.getCarNumber())
                    .status("ON")
                    .build();


            rabbitMqPublisher.sendMessage(DRIVE_LOG_EXCHANGE, "drive.log.ON", driveLogOnEvent);


            log.info("새로운 스케줄러를 시작합니다: {}", carNumber);
            GpxScheduler gpxScheduler = new GpxScheduler(restTemplate, gpxSchedulerPool, carRepository, updateCarStatusService);
            schedulers.put(carNumber, gpxScheduler);
            gpxScheduler.init(carNumber, loginId);

        } else if (powerStatus.equals("OFF")) {
            updateCarStatusService.updateCarStatusAsync(carNumber, CarStatus.IDLE);


            DriveLogEventDto driveLogOffEvent = DriveLogEventDto.builder()
                    .eventTime(LocalDateTime.now())
                    .carNumber(carEntity.getCarNumber())
                    .status("OFF")
                    .build();

            rabbitMqPublisher.sendMessage(DRIVE_LOG_EXCHANGE, "drive.log.OFF", driveLogOffEvent);


            if(schedulers.containsKey(carNumber)){
                try{
                    log.info("실행중인 스케줄러를 중지합니다: {}", carNumber);
                    schedulers.get(carNumber).stopScheduler();
                }catch (Exception e){
                    log.error("stopScheduler() 호출 중 예외 발생 (무시하고 제거 진행): {}", e.getMessage(), e);
                }finally{
                    log.info("스케줄러를 맵에서 제거합니다: {}", carNumber);
                    schedulers.remove(carNumber);
                }
            }else{
                log.warn("중지할 스케줄러를 찾을 수 없습니다: {}", carNumber);
            }

            log.info("차량 상태 OFF: {}", carNumber);



        }

        return LogPowerDto.builder()
                .carNumber(carEntity.getCarNumber())
                .loginId(loginId)
                .powerStatus(carEntity.getStatus().getDisplayName())
                .build();
    }
}
package com.example.emulator.application;

import com.example.emulator.application.dto.DriveLogEventDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RabbitMqPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Async("rabbitDriveLogEvent")
    public void sendMessage(String exchange, String routingKey, DriveLogEventDto message) {
        rabbitTemplate.convertAndSend(exchange, routingKey, message);
    }


}

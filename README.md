## 🚗 GPX 기반 차량 운행 에뮬레이터

본 프로젝트는 GPX 파일을 기반으로 차량 운행 데이터를 시뮬레이션하고,
생성된 로그를 차량 관제 시스템 서버로 전송하는 Spring Boot 애플리케이션입니다.

## ✨ 주요 기능

## 차량 목록 조회 
특정 사용자가 보유한 차량 목록을 조회

## 운행 시뮬레이션

차량의 시동을 걸면(ON) 프로젝트 내 GPX 파일 중 하나를 무작위로 선택하여 최소 5분간 운행 시뮬레이션 수행

## 1초마다 위도/경도 데이터 생성

주기 마다 누적된 운행 데이터를 외부 로그 수집 서버로 전송
시동을 끄면(OFF) 즉시 시뮬레이션 중단 및 남은 데이터 전송

## 🛠️ 사용 기술

Java 17

Spring Boot 3.x

Gradle

Lombok

EC2

Docker

Jenkins

Swagger

## 🚀 실행 방법
사전 요구사항

JDK 17 이상

Gradle

## 빌드 및 실행

## 1. 프로젝트 클론

git clone <repository-url>
cd emulator


## 2. 프로젝트 빌드

./gradlew build


## 3. 애플리케이션 실행

./gradlew bootRun


## 또는 빌드된 JAR 실행:

java -jar build/libs/emulator-0.0.1-SNAPSHOT.jar

## 기본 실행 포트: 8081

## ⚙️ 시뮬레이션 상세 로직

POST /api/logs/power 요청에서 power: ON 수신 시 LogService → GpxScheduler 실행

src/main/resources/gpx 경로의 GPX 파일 중 하나를 무작위 선택

선택된 GPX 파일의 랜덤 지점부터 5분(300개 <trkpt>) 데이터를 기반으로 시뮬레이션 진행

ScheduledExecutorService 사용 → 1초마다 GPX 데이터 읽기 → 로그 생성 후 버퍼에 저장

시뮬레이션 시작 후 60초마다 버퍼에 쌓인 로그 데이터를 외부 API
(http://52.78.122.150:8080/api/logs/gps)로 전송 후 버퍼 초기화

power: OFF 요청이 오거나 5분이 끝나면 스케줄러 중단 → 마지막 버퍼 데이터 전송 → 운행 종료


package com.example.emulator.application.exception;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class GpxExceptionGenerator {

    /**
     * GPX 데이터 리스트에 '신호 끊김(프리즈)' 예외 상황을 시뮬레이션
     * @param originalLines 원본 GPX 파일 라인 리스트
     * @param freezeStartIndex 프리즈를 시작할 인덱스
     * @param freezeDuration 프리즈 지속 시간 (라인 수)
     * @return 프리즈가 적용된 새로운 라인 리스트
     */
    public static List<String> introduceFreeze(List<String> originalLines, int freezeStartIndex, int freezeDuration){
        if (originalLines == null || originalLines.isEmpty() || freezeStartIndex < 0 || freezeStartIndex >= originalLines.size()){
            return originalLines;
        }

        log.info("신호 끊김(프리즈) 시뮬레이션 적용. 시작 인덱스: {}, 지속 시간: {}", freezeStartIndex, freezeDuration);
        List<String> modifiedList = new ArrayList<>(originalLines);
        String freezeLine = modifiedList.get(freezeStartIndex); // 고정될 좌표 라인

        int end = Math.min(freezeStartIndex + freezeDuration, modifiedList.size());
        for(int i = freezeStartIndex + 1; i < end; i++) {
            modifiedList.set(i, freezeLine); // 지정된 시간 동안 동일한 좌표 라인으로 덮어쓰기
        }
        return modifiedList;
    }

    /**
     * GPX 파일 라인 리스트에 '신호 끊김(삭제)' 예외 상황을 시뮬레이션.
     * 해당 라인들을 "DELETION_MARKER"로 교체하여, 스케줄러가 이 마커를 인지하고 해당 시간 동안 데이터 전송을 일시 중지하도록 유도.
     * @param originalLines 원본 GPX 파일 라인 리스트
     * @param deletionStartIndex 삭제를 시작할 인덱스
     * @param deletionCount 삭제할 라인 수 (일시 중지 시간)
     * @return "DELETION_MARKER"가 적용된 새로운 리스트
     */
    public static List<String> introduceDeletion(List<String> originalLines, int deletionStartIndex, int deletionCount) {
        if (originalLines == null || originalLines.isEmpty() || deletionStartIndex < 0 || deletionCount <= 0) {
            return originalLines;
        }
        log.info("신호 끊김(삭제) 시뮬레이션 적용. 시작 인덱스: {}, 중지 시간: {}초", deletionStartIndex, deletionCount);

        List<String> modifiedList = new ArrayList<>(originalLines);
        int endIndex = Math.min(deletionStartIndex + deletionCount, modifiedList.size());

        for (int i = deletionStartIndex; i < endIndex; i++) {
            modifiedList.set(i, "DELETION_MARKER");
        }
        return modifiedList;
    }
}
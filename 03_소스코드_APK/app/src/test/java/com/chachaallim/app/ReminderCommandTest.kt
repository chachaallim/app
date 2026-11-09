package com.chachaallim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderCommandTest {
    @Test
    fun extractsItemFromSupportedPhrasings() {
        val examples = mapOf(
            "내릴 때 노트북 챙기라고 알려줘" to "노트북",
            "내릴 때 우산 챙겨줘" to "우산",
            "내릴 때는, 우산 챙겨 줘" to "우산",
            "주차하면 서류를 가져가라고 알려줘." to "서류",
            "차에서 내릴 때  검은색 우산을 챙겨 달라고 알려 주세요!" to "검은색 우산",
            "다음 주차할 때 USB를 챙겨주세요" to "USB",
            "가을 챙겨줘" to "가을"
        )
        examples.forEach { (command, item) ->
            assertEquals(command, item, ReminderCommand.itemFrom(command))
        }
    }

    @Test
    fun rejectsMissingItemsAndUnrelatedRequests() {
        listOf(
            "",
            "내릴 때 챙겨줘",
            "주차하면 가져가라고 알려줘",
            "내일 우산 챙겨줘",
            "우산 안 챙겨줘",
            "우산 챙기지 말라고 알려줘",
            "내릴 때 전화해줘"
        ).forEach { command -> assertNull(command, ReminderCommand.itemFrom(command)) }
    }
    @Test
    fun rewritesRemindersWithoutAnActionList() {
        val examples = mapOf(
            "내릴 때 노트북 챙기라고 알려줘" to "노트북 챙기세요.",
            "내릴 때 김치통 챙기는 거 알려줘" to "김치통 챙기는 걸 잊지 마세요.",
            "내릴 때 김치통을 가지고 내리라고 알려줘" to "김치통을 가지고 내리세요.",
            "내릴 때 김치통을 가지고 내리지 말라고 알려줘" to "김치통을 가지고 내리지 마세요.",
            "다음 주차 때 7시 미팅 자료를 챙기라고 알려줘" to "7시 미팅 자료를 챙기세요.",
            "내릴 때 내일 입을 운동복을 뒷좌석에서 꺼내 가라고 해줘" to "내일 입을 운동복을 뒷좌석에서 꺼내 가세요.",
            "주차하자마자 오른쪽 문은 열지 말고 왼쪽 주머니에서 카드만 꺼내 가라고 알려줘" to
                "오른쪽 문은 열지 말고 왼쪽 주머니에서 카드만 꺼내 가세요.",
            "주차를 마치면 2열 컵홀더 안에 있는 동전 지갑을 챙겨 나오라고 전해줘" to
                "2열 컵홀더 안에 있는 동전 지갑을 챙겨 나오세요.",
            "차에서 내리거든 그 파란 우산만 뒷좌석에 남겨 두라고 말해 줘" to
                "그 파란 우산만 뒷좌석에 남겨 두세요.",
            "차 세우고 나면 뒷좌석 서류 봉투 꺼내 가는 거 잊지 않게 해줘" to
                "뒷좌석 서류 봉투 꺼내 가는 걸 잊지 마세요.",
            "주차하면 안내 방송을 듣는 걸 알려 주세요" to "안내 방송을 듣는 걸 잊지 마세요.",
            "내릴 때 문 닫는 것을 말해 줘" to "문 닫는 걸 잊지 마세요.",
            "내릴 때 노트북 챙겨 가라고 알려줘" to "노트북 챙겨 가세요.",
            "내릴때 미팅약속 잊지마라고 알려줘" to "미팅약속 잊지 마세요.",
            "내릴 때 미팅 약속을 잊지 말라고 알려줘" to "미팅 약속을 잊지 마세요.",
            "내릴 때 트렁크에 식혜 꺼내가라고 알려줘" to "트렁크에서 식혜를 꺼내 가세요.",
            "트렁크에 식혜 있어. 차에서 내릴 때 챙기라고 알려줘." to "트렁크에 있는 식혜 챙기세요.",
            "내릴 때 조수석 발밑에 있는 장화를 신으라고 일러 줘" to "조수석 발밑에 있는 장화를 신으세요.",
            "주차하면 수진 씨에게 전화하라고 알려줘" to "수진 씨에게 전화하세요.",
            "차 세우고 나면 카드키 챙기고 창문 닫으라고 말해줘" to "카드키 챙기고 창문 닫으세요.",
            "주차하면 노란 봉투만 챙기고 빨간 봉투는 두고 가라 그래" to "노란 봉투만 챙기고 빨간 봉투는 두고 가세요.",
            "내릴 때 택배 찾는 거 잊지 않게 해줘" to "택배 찾는 걸 잊지 마세요.",
            "미팅 예약을 잊지 않도록 해줘" to "미팅 예약을 잊지 마세요.",
            "내릴 때 미팅 약속 있다고 알려줘" to "미팅 약속 있어요.",
            "내릴 때 미팅 약속이 있다고 알려줘" to "미팅 약속이 있어요.",
            "주차하면 트렁크에 식혜가 있다고 알려 주세요" to "트렁크에 식혜가 있어요.",
            "내릴 때 우산이 없다고 말해줘" to "우산이 없어요."
        )
        examples.forEach { (command, message) ->
            assertEquals(command, message, ReminderCommand.parkingMessage(command))
        }
    }

    @Test
    fun refusesUnclearRequestsAndOtherReminderTimes() {
        listOf(
            "내일 아침 8시에 미팅 약속 잊지 말라고 알려줘",
            "트렁크에 있는 식혜 내일 때 챙겨가라 해줘",
            "집에 도착하면 전화하라고 알려줘",
            "회사 문 열 때 지하 주차권 챙기라고 알려줘",
            "빨간불에 멈추면 뒤에 둔 봉투를 챙기라고 해 줘",
            "내릴 때 말고 두 시간 뒤에 가방 챙기라고 알려줘",
            "주차하면 그거만 챙기라고 알려줘",
            "차에서 내리거든 그걸 바닥에 두고 가라고 알려줘",
            "오전 열한 시 반에 보조배터리 찾으라고 알려줘",
            "지금 트렁크 열어줘",
            "내릴 때 잊지 말라고 알려줘",
            "내릴 때 그거 잊지 말라고 알려줘",
            "내일 미팅 약속 있다고 알려줘",
            "내일 김치통 챙기는 거 알려줘",
            "내릴 때 챙길 거 알려줘",
            "내릴 때 있다고 알려줘",
            "내릴 때 그거 있다고 알려줘",
            "콘솔 안에 명함 두 장이 있어. 차에서 내리기 전에 명함을 챙기라고 알려줘",
            "주차하면 회의 알림 좀"
        ).forEach { command -> assertNull(command, ReminderCommand.parkingMessage(command)) }
    }
}

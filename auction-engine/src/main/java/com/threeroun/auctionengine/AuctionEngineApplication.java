package com.threeroun.auctionengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.ZoneId;
import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class AuctionEngineApplication {

	// 이 서비스의 시각은 전부 시간대 정보가 없는 LocalDateTime이다(마감 시각, 입찰 시각, 로그인 만료 시각 등).
	// 사용자는 한국 시각으로 마감 시각을 입력하는데, 서버(컨테이너)의 시스템 시간대는 보통 UTC라서
	// 서버의 LocalDateTime.now()와 입력값이 서로 다른 기준이 되어, 마감 처리가 9시간 늦게 일어났다 (이슈 #152).
	// 그래서 서버가 어디서 돌든(로컬 PC, CI, 컨테이너) 같은 기준이 되도록 JVM 기본 시간대를 코드에서 고정한다.
	// 환경 설정(TZ 환경변수 등)에 맡기지 않는 이유: 슬림 컨테이너 이미지에서는 OS 시간대 정보가 없어
	// TZ 값이 조용히 무시될 수 있고, 로컬/CI/운영이 서로 달라지는 것을 코드 한 곳에서 막을 수 있다.
	static final String DEFAULT_TIME_ZONE = "Asia/Seoul";

	public static void main(String[] args) {
		// 스프링 컨텍스트가 만들어지기 전에 설정해야 이후 생성되는 모든 빈이 같은 시간대를 본다.
		applyDefaultTimeZone(System.getenv().getOrDefault("APP_TIMEZONE", DEFAULT_TIME_ZONE));
		SpringApplication.run(AuctionEngineApplication.class, args);
	}

	// ZoneId.of로 먼저 검증한다 - TimeZone.getTimeZone("잘못된값")은 예외 없이 조용히 GMT로 떨어져서,
	// 오타 하나로 9시간 어긋난 서버가 아무 경고 없이 뜨는 것을 막기 위해 잘못된 값이면 기동을 실패시킨다.
	static void applyDefaultTimeZone(String zoneId) {
		TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zoneId)));
	}

}

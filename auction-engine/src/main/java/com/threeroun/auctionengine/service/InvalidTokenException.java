package com.threeroun.auctionengine.service;

// JwtAuthenticationFilter가 서블릿 필터 단계에서 직접 잡아서 401 응답을 쓰기 위한 예외.
// 디스패처서블릿 이전 단계라 @RestControllerAdvice(ApiExceptionHandler)로는 못 잡는다.
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException() {
        super("토큰이 없거나 유효하지 않습니다");
    }
}

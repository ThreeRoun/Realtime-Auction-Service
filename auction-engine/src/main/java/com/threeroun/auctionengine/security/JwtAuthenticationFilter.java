package com.threeroun.auctionengine.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeroun.auctionengine.controller.ErrorResponse;
import com.threeroun.auctionengine.service.InvalidTokenException;
import com.threeroun.auctionengine.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

// 상품등록/입찰처럼 "누가 요청했는지"가 중요한 API 앞단에서 Authorization 헤더의 JWT를 검증해서
// request attribute(authUserId)로 심어준다. 디스패처서블릿보다 앞단(서블릿 필터)이라
// @RestControllerAdvice(ApiExceptionHandler)가 여기서 던진 예외를 못 잡으므로, 실패 응답은
// 이 필터가 직접 써야 한다.
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_USER_ID_ATTRIBUTE = "authUserId";

    // 토큰 검증이 필요한 엔드포인트만 명시적으로 지정한다 (화이트리스트 방식) - 조회 API,
    // 회원가입, 로그인은 인증 없이 호출돼야 하므로 여기 넣지 않는다.
    private static final Set<String> PROTECTED_POST_PATHS = Set.of("/api/products", "/api/bids");

    private final JwtService jwtService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!isProtected(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeUnauthorized(response, "Authorization 헤더가 없습니다");
            return;
        }

        try {
            UUID userId = jwtService.parseUserId(header.substring("Bearer ".length()));
            request.setAttribute(AUTH_USER_ID_ATTRIBUTE, userId);
        } catch (InvalidTokenException e) {
            writeUnauthorized(response, e.getMessage());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isProtected(HttpServletRequest request) {
        // 기본("/") 디스패처 매핑에서는 getServletPath()가 빈 문자열을 반환하는 서블릿 컨테이너가 있어
        // 경로 매칭에 getRequestURI()를 쓴다 (컨텍스트 패스가 없는 구성이라 그대로 비교 가능).
        return "POST".equalsIgnoreCase(request.getMethod()) && PROTECTED_POST_PATHS.contains(request.getRequestURI());
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        // charset을 명시 안 하면 서블릿 컨테이너 기본값(ISO-8859-1)으로 써서 한글 메시지가 깨진다.
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(new ErrorResponse("UNAUTHORIZED", message)));
    }
}

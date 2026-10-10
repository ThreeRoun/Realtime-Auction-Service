package com.threeroun.auctionengine.controller;

import com.threeroun.auctionengine.service.AuctionNotInProgressException;
import com.threeroun.auctionengine.service.DuplicateUserException;
import com.threeroun.auctionengine.service.InvalidCredentialsException;
import com.threeroun.auctionengine.service.ProductNotFoundException;
import com.threeroun.auctionengine.service.SelfBidNotAllowedException;
import com.threeroun.auctionengine.service.UserNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

// docs/bid-api.md에 정리된 에러 코드 계약을 그대로 따른다.
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleProductNotFound(ProductNotFoundException e) {
        return new ErrorResponse("PRODUCT_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleUserNotFound(UserNotFoundException e) {
        return new ErrorResponse("USER_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(AuctionNotInProgressException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleAuctionNotInProgress(AuctionNotInProgressException e) {
        return new ErrorResponse("AUCTION_NOT_IN_PROGRESS", e.getMessage());
    }

    @ExceptionHandler(DuplicateUserException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDuplicateUser(DuplicateUserException e) {
        return new ErrorResponse("DUPLICATE_USER", e.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleInvalidCredentials(InvalidCredentialsException e) {
        return new ErrorResponse("INVALID_CREDENTIALS", e.getMessage());
    }

    @ExceptionHandler(SelfBidNotAllowedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleSelfBidNotAllowed(SelfBidNotAllowedException e) {
        return new ErrorResponse("SELF_BID_NOT_ALLOWED", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleInvalidRequest(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return new ErrorResponse("INVALID_REQUEST", message);
    }
}

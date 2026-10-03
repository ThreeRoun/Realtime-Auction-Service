package com.threeroun.auctionengine.service;

public class DuplicateUserException extends RuntimeException {

    public DuplicateUserException(String field, String value) {
        super("이미 사용 중인 " + field + "입니다: " + value);
    }
}

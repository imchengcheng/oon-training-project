package edu.campus.rag.dto;

public record ApiResponse<T>(String code, String message, T data, String requestId) {}

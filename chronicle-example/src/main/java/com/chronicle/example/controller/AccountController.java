package com.chronicle.example.controller;

import com.chronicle.example.dto.AccountResponse;
import com.chronicle.example.dto.CreateAccountRequest;
import com.chronicle.example.dto.DepositRequest;
import com.chronicle.example.dto.EventResponse;
import com.chronicle.example.dto.TransferRequest;
import com.chronicle.example.dto.WithdrawRequest;
import com.chronicle.example.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Bank Accounts", description = "Event-sourced bank account operations")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new bank account")
    public AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest req) {
        return accountService.createAccount(req);
    }

    @PostMapping("/{id}/deposit")
    @Operation(summary = "Deposit money into an account")
    public AccountResponse deposit(@PathVariable UUID id, @Valid @RequestBody DepositRequest req) {
        return accountService.deposit(id, req);
    }

    @PostMapping("/{id}/withdraw")
    @Operation(summary = "Withdraw money from an account")
    public AccountResponse withdraw(@PathVariable UUID id, @Valid @RequestBody WithdrawRequest req) {
        return accountService.withdraw(id, req);
    }

    @PostMapping("/{id}/transfer")
    @Operation(summary = "Transfer money to another account")
    public AccountResponse transfer(@PathVariable UUID id, @Valid @RequestBody TransferRequest req) {
        return accountService.transfer(id, req);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get account details from projection")
    public AccountResponse getAccount(@PathVariable UUID id) {
        return accountService.getAccount(id);
    }

    @GetMapping("/{id}/events")
    @Operation(summary = "Get account event history")
    public List<EventResponse> getEvents(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer after) {
        return accountService.getEvents(id, after);
    }
}

package com.eazybytes.accounts.service.impl;

import com.eazybytes.accounts.dto.AccountsDto;
import com.eazybytes.accounts.dto.CardsDto;
import com.eazybytes.accounts.dto.CustomerDetailsDto;
import com.eazybytes.accounts.dto.LoansDto;
import com.eazybytes.accounts.entity.Accounts;
import com.eazybytes.accounts.entity.Customer;
import com.eazybytes.accounts.exception.ResourceNotFoundException;
import com.eazybytes.accounts.mapper.AccountsMapper;
import com.eazybytes.accounts.mapper.CustomerMapper;
import com.eazybytes.accounts.repository.AccountsRepository;
import com.eazybytes.accounts.repository.CustomerRepository;
import com.eazybytes.accounts.service.ICustomersService;
import com.eazybytes.accounts.service.client.CardsFeignClient;
import com.eazybytes.accounts.service.client.LoansFeignClient;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@AllArgsConstructor
@Slf4j
public class CustomersServiceImpl implements ICustomersService {

    private AccountsRepository accountsRepository;
    private CustomerRepository customerRepository;
    private CardsFeignClient cardsFeignClient;
    private LoansFeignClient loansFeignClient;

    /**
     * @param mobileNumber  - Input Mobile Number
     * @param correlationId - Correlation ID value generated at Edge server
     * @return Customer Details based on a given mobileNumber
     */
    @Override
    public CustomerDetailsDto fetchCustomerDetails(String mobileNumber, String correlationId) {
        Customer customer = customerRepository.findByMobileNumber(mobileNumber).orElseThrow(
                () -> new ResourceNotFoundException("Customer", "mobileNumber", mobileNumber)
        );
        Accounts accounts = accountsRepository.findByCustomerId(customer.getCustomerId()).orElseThrow(
                () -> new ResourceNotFoundException("Account", "customerId", customer.getCustomerId().toString())
        );

        CustomerDetailsDto customerDetailsDto = CustomerMapper.mapToCustomerDetailsDto(customer, new CustomerDetailsDto());
        customerDetailsDto.setAccountsDto(AccountsMapper.mapToAccountsDto(accounts, new AccountsDto()));

        fetchApiAsync(mobileNumber, correlationId, customerDetailsDto);

        return customerDetailsDto;

    }

    private void fetchApiAsync(String mobileNumber, String correlationId, CustomerDetailsDto customerDetailsDto) {
        final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        var completableFutureCardRs = CompletableFuture
                .supplyAsync(() -> cardsFeignClient.fetchCardDetails(correlationId, mobileNumber), virtualThreadExecutor)
                .exceptionally(exp -> {
                    log.error("Error fetching card details {}", exp.getMessage());
                    return null;
                }).thenAccept(cardDetails -> {
                    customerDetailsDto.setCardsDto(cardDetails == null ? null : cardDetails.getBody());
                });
        var completableFutureLoanRs = CompletableFuture.supplyAsync(() -> loansFeignClient.fetchLoanDetails(correlationId, mobileNumber), virtualThreadExecutor)
                .exceptionally(exp -> {
                    log.error("Error fetching loan details {}", exp.getMessage());
                    return null;
                }).thenAccept(loanDetails -> {
                    customerDetailsDto.setLoansDto(loanDetails == null ? null : loanDetails.getBody());
                });
        CompletableFuture.allOf(completableFutureLoanRs, completableFutureCardRs).join();
        virtualThreadExecutor.shutdown();
    }
}

package com.example.payment;

import java.math.BigDecimal;

public class EligibilityQuery {
    private String requesterId;
    private BigDecimal amount;

    public String getRequesterId() { return requesterId; }
    public void setRequesterId(String requesterId) { this.requesterId = requesterId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}

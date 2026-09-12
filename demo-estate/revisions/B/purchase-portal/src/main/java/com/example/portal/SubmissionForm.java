package com.example.portal;

import java.math.BigDecimal;

public class SubmissionForm {
    private String requesterId;
    private String costCentre;
    private BigDecimal amount;
    private String justification;

    public String getRequesterId() { return requesterId; }
    public void setRequesterId(String requesterId) { this.requesterId = requesterId; }
    public String getCostCentre() { return costCentre; }
    public void setCostCentre(String costCentre) { this.costCentre = costCentre; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getJustification() { return justification; }
    public void setJustification(String justification) { this.justification = justification; }
}

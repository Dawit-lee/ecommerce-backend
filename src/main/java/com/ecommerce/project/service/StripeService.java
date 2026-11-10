package com.ecommerce.project.service;

import com.ecommerce.project.payload.StripePaymentDto;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;

public interface StripeService {

    PaymentIntent paymentIntent(StripePaymentDto stripePaymentDto) throws StripeException;

    // Compensating action: refunds a previously captured PaymentIntent when order placement fails.
    Refund refundPayment(String paymentIntentId) throws StripeException;
}

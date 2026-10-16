package org.token.model.query;

/** 本地充值地址的永久归属匹配条件。 */
public record DepositAddressIdentity(String merchant, String environment, long memberId, String wallet,
                                     String networkId, String addressId, String address) { }

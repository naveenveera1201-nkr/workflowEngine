package com.constant;

public enum MutualFundContant {

	P100("p100"), P101("p101"), INVESTOR_PROFILE_REGISTRATION("p102"), INVESTOR_BANKDETAILS_REGISTRATION("p103"),
	INVESTOR_KYC_REGISTRATION("p104"), INVESTOR_NOMINEE_REGISTRATION("p105");

	private final String code;

	MutualFundContant(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public boolean equals(String value) {
		return code.equalsIgnoreCase(value);
	}

}

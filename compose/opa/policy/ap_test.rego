package ap_test

import rego.v1

import data.ap

proposal(action, facts) := {
	"toolName": "propose_resolution",
	"arguments": {"action": action, "rationale": "r", "evidence": []},
	"facts": facts,
}

price_variance := {"reasonCode": "PRICE_VARIANCE", "amountAtIssue": 40, "buyer": "bob"}

test_reads_are_allowed if {
	ap.decision == {"effect": "allow"} with input as {"toolName": "get_invoice", "arguments": {}, "facts": {}}
}

test_hold_goes_to_a_clerk if {
	ap.decision == {"effect": "delegate", "to": "ap-clerk"} with input as proposal("hold", price_variance)
}

test_credit_memo_goes_to_a_clerk if {
	ap.decision.to == "ap-clerk" with input as proposal("request-credit-memo", price_variance)
}

test_a_small_price_variance_goes_to_the_pos_buyer if {
	ap.decision == {"effect": "delegate", "to": "buyer", "buyer": "bob"} with input as proposal("approve-variance", price_variance)
}

test_other_approvals_go_to_the_ap_manager if {
	ap.decision.to == "ap-manager" with input as proposal(
		"approve-variance",
		{"reasonCode": "UNPLANNED_CHARGE", "amountAtIssue": 1600},
	)
}

test_a_rejection_goes_to_the_ap_manager if {
	ap.decision.to == "ap-manager" with input as proposal("reject", {"reasonCode": "DUPLICATE", "amountAtIssue": 1000})
}

test_above_ten_thousand_goes_to_the_controller if {
	ap.decision.to == "controller" with input as proposal(
		"approve-variance",
		{"reasonCode": "PRICE_VARIANCE", "amountAtIssue": 12000, "buyer": "bob"},
	)
}

test_a_short_pay_is_judged_by_what_it_pays if {
	ap.decision.to == "controller" with input as {
		"toolName": "propose_resolution",
		"arguments": {"action": "short-pay", "amount": 12000, "rationale": "r", "evidence": []},
		"facts": {"reasonCode": "QTY_OVER_RECEIPT", "amountAtIssue": 400},
	}
}

test_paying_a_vendor_with_an_unverified_bank_change_is_denied if {
	ap.decision.effect == "deny" with input as proposal(
		"approve-variance",
		{"reasonCode": "VENDOR_BANK_CHANGED", "amountAtIssue": 1000, "bankChangeUnverified": true},
	)
}

test_holding_a_vendor_with_an_unverified_bank_change_is_routed if {
	ap.decision.to == "ap-clerk" with input as proposal(
		"hold",
		{"reasonCode": "VENDOR_BANK_CHANGED", "amountAtIssue": 1000, "bankChangeUnverified": true},
	)
}

test_an_unknown_action_is_denied if {
	ap.decision.effect == "deny" with input as proposal("pay-twice", price_variance)
}

test_a_missing_buyer_falls_back_to_the_ap_manager if {
	ap.decision.to == "ap-manager" with input as proposal("approve-variance", {"reasonCode": "PRICE_VARIANCE", "amountAtIssue": 40})
}

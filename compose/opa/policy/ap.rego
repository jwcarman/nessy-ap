# Who must decide a proposed resolution. Routing is policy, kept as data: the agent proposes,
# this names the role that decides, and the ERP enforces what that role may do.
package ap

import rego.v1

# Fail closed: a policy that matches nothing denies, it never approves.
default decision := {"effect": "deny", "reason": "no policy rule matched"}

# Only proposals are routed; every other tool the agent has is read-only.
decision := {"effect": "allow"} if input.toolName != "propose_resolution"

decision := resolution if input.toolName == "propose_resolution"

actions := {"approve-variance", "short-pay", "hold", "reject", "request-credit-memo"}

moves_money := {"approve-variance", "short-pay", "request-credit-memo"}

limit := 10000

action := input.arguments.action

# Facts the policy cannot see never read as safe: no word on the bank details means unverified,
# and no amount in question means no payment can be routed.
bank_unverified := object.get(input.facts, "bankChangeUnverified", true)

at_issue := input.facts.amountAtIssue if is_number(input.facts.amountAtIssue)

short_pay_amount := input.arguments.amount if is_number(input.arguments.amount)

# What a decision puts at stake. A short-pay is judged by the larger of what it pays and what the
# exception questioned, so the model cannot choose a small amount to choose a junior reviewer.
amount := max({short_pay_amount, at_issue}) if action == "short-pay"

else := at_issue

resolution := {"effect": "deny", "reason": sprintf("%v is not a resolution", [action])} if {
	not action in actions
}

else := {"effect": "deny", "reason": "a short-pay needs a positive amount"} if {
	action == "short-pay"
	not short_pay_amount > 0
}

# The payment-fraud pattern: nothing that pays the vendor until the bank change is verified.
else := {
	"effect": "deny",
	"reason": "the vendor has an unverified bank-detail change: only hold or reject",
} if {
	action in moves_money
	bank_unverified != false
}

else := {"effect": "deny", "reason": "the amount in question is unknown"} if {
	action in moves_money
	not at_issue
}

else := {"effect": "delegate", "to": "ap-clerk"} if {
	action in {"hold", "request-credit-memo"}
}

else := {"effect": "delegate", "to": "controller"} if amount > limit

# A price the buyer may have agreed is the buyer's to accept: the one who placed the order.
else := {"effect": "delegate", "to": "buyer", "buyer": input.facts.buyer} if {
	action == "approve-variance"
	input.facts.reasonCode == "PRICE_VARIANCE"
	input.facts.buyer
}

else := {"effect": "delegate", "to": "ap-manager"}

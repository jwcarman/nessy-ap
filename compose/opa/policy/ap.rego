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

# What a decision puts at stake: a short-pay authorises what it pays; anything else, what the
# exception questioned.
amount := to_number(input.arguments.amount) if action == "short-pay"

else := to_number(object.get(input.facts, "amountAtIssue", 0))

resolution := {"effect": "deny", "reason": sprintf("%v is not a resolution", [action])} if {
	not action in actions
}

# The payment-fraud pattern: nothing that pays the vendor until the bank change is verified.
else := {
	"effect": "deny",
	"reason": "the vendor has an unverified bank-detail change: only hold or reject",
} if {
	object.get(input.facts, "bankChangeUnverified", false) == true
	action in moves_money
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

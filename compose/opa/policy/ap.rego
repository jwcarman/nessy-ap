# Who must decide a proposed resolution. Routing is policy, kept as data: the agent proposes,
# this names the role that decides, and the ERP enforces what that role may do.
package ap

import rego.v1

# Fail closed: a policy that matches nothing denies, it never approves.
default decision := {"effect": "deny", "reason": "no policy rule matched"}

# Tools named here need no approval: they read, or write only to the case and to the workbench.
# Proposals are routed and vendor mail is checked below. A tool this policy does not name falls
# to the default and is denied, so an app newer than its policy fails closed, never open.
ungated := {
	"get_invoice", "get_purchase_order", "get_receipts", "get_vendor",
	"find_similar_invoices", "get_vendor_invoice_history", "note_case", "ask_buyer",
}

decision := {"effect": "allow"} if input.toolName in ungated

decision := resolution if input.toolName == "propose_resolution"

decision := vendor_mail if input.toolName == "email_vendor"

# Never write to a vendor whose bank details changed unverified: whoever asked for the change may be
# the one reading, and a reply on that thread can look like the vendor confirming it.
vendor_mail := {"effect": "deny", "reason": bank_unknown_reason} if {
	not bank_known
}

else := {
	"effect": "deny",
	"reason": "the vendor has an unverified bank-detail change: do not email them; hold the invoice and have the change verified by calling the contact of record",
} if {
	bank_unverified != false
}

else := {"effect": "allow"}

actions := {"approve-variance", "short-pay", "hold", "reject", "request-credit-memo"}

moves_money := {"approve-variance", "short-pay", "request-credit-memo"}

limit := 10000

action := input.arguments.action

# Facts the policy cannot see never read as safe: no word on the bank details means unverified,
# and no amount in question means no payment can be routed.
bank_unverified := object.get(input.facts, "bankChangeUnverified", true)

# Unknown is refused too, but never reported as fraud: a read that failed says nothing about the
# bank details, and telling the agent "fraud" would make it hold a good invoice.
bank_known if is_boolean(input.facts.bankChangeUnverified)

# A repeat is a repeat through any of its exceptions: a re-sent overpriced invoice raises both a
# duplicate and a price variance, and approving the variance would pay it all the same.
repeats_a_number if input.facts.reasonCode == "DUPLICATE"

repeats_a_number if "DUPLICATE" in object.get(input.facts, "openReasonCodes", [])

bank_unknown_reason := "could not read the vendor's bank details just now; try again shortly"

at_issue := input.facts.amountAtIssue if is_number(input.facts.amountAtIssue)

short_pay_amount := input.arguments.amount if is_number(input.arguments.amount)

# The ERP checks a decider's authority against the invoice total (a short-pay's own amount for a
# short-pay), so routing must never send a decision lower than the ERP will accept. An unknown total
# reads as above every limit but the controller's.
invoice_total := input.facts.invoiceTotal if is_number(input.facts.invoiceTotal)

else := limit + 1

# What a decision puts at stake. A short-pay is judged by the larger of what it pays and what the
# exception questioned, so the model cannot choose a small amount to choose a junior reviewer.
amount := max({short_pay_amount, at_issue}) if action == "short-pay"

else := max({at_issue, invoice_total})

resolution := {"effect": "deny", "reason": sprintf("%v is not a resolution", [action])} if {
	not action in actions
}

# An approver relies on each citation meaning "the agent read this". Measured: four times in about
# 1,650 runs a model copied an id wrongly (a digit changed, a character dropped, an id abandoned
# halfway) and the proposal reached a person. Refused here, the agent corrects it in the same turn.
else := {
	"effect": "deny",
	"reason": sprintf(
		"your evidence cites %v, which no tool returned to you: cite only ids a tool returned, copied exactly, then propose again",
		[concat(", ", input.facts.ungroundedCitations)],
	),
} if {
	count(object.get(input.facts, "ungroundedCitations", [])) > 0
}

# A hold over an unverified bank change rests on the vendor, so its evidence must say so. Measured:
# a model held correctly and cited the invoice, a receipt and the PO, but not the vendor, in 5 of
# 20 runs. The reason names the record, never an id: an id copied from a refusal is one no tool
# returned. Only when the vendor was read: an unreadable vendor has its own refusal.
else := {
	"effect": "deny",
	"reason": "this hold rests on the vendor's unverified bank change: cite the vendor you read (its id, or the id of its pending bank change) in your evidence, then propose again",
} if {
	action == "hold"
	bank_known
	bank_unverified == true
	not input.facts.citesVendor
}

# An agent that asked someone in this turn has not seen the answer. Measured: an agent asked the
# buyer, then proposed approval on an answer it made up, and the overcharge was paid.
else := {
	"effect": "deny",
	"reason": "you asked someone in this turn: end the turn and wait for the answer before you propose",
} if {
	input.facts.askedThisTurn == true
}

# Nobody is asked to approve a decision that changes nothing: a person's time is the scarce thing.
else := {"effect": "deny", "reason": "the invoice is already on hold: there is nothing to decide"} if {
	action == "hold"
	input.facts.invoiceStatus == "ON_HOLD"
}

else := {"effect": "deny", "reason": "a short-pay needs a positive amount"} if {
	action == "short-pay"
	not short_pay_amount > 0
}

else := {"effect": "deny", "reason": bank_unknown_reason} if {
	action in moves_money
	not bank_known
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

# Information flow: the case's agent has read mail that tried to instruct the desk or claimed an
# approval. Everything it proposes after that is influenced by an attacker's words, so nothing that
# moves money goes through on this case, ever: nothing in the desk lowers the flag, and a person
# settles the invoice in the ERP. It fails safe: the worst an attacker
# can do with it is force a hold.
else := {
	"effect": "deny",
	"reason": "a reply on this case tried to instruct the desk or claimed an approval, or could not be read: the desk does not move money on this case; a person settles it in the ERP",
} if {
	action in moves_money
	object.get(input.facts, "instructionsSeen", false) == true
}

# The same invoice number as one already received is a repeat: nothing pays it from the desk, however
# its text argues. Reject it (or hold it while someone looks).
else := {
	"effect": "deny",
	"reason": "an invoice with the same number as one already received is never paid from the desk: reject it",
} if {
	repeats_a_number
	action in moves_money
}

# A credit memo was requested for this invoice: the vendor owes a credit against it. Paying the
# invoice now would pay what the credit is meant to take back, so a person settles it in the ERP.
else := {
	"effect": "deny",
	"reason": "a credit memo was already requested for this invoice: settle that in the ERP before paying it",
} if {
	object.get(input.facts, "creditMemoRequested", false) == true
	action in {"approve-variance", "short-pay"}
}

else := {"effect": "delegate", "to": "ap-clerk"} if {
	action in {"hold", "request-credit-memo"}
}

# The same order and total a few days apart may be a second shipment billed alike. Paying it is the
# controller's call at any amount, once the receipts show two deliveries.
else := {"effect": "delegate", "to": "controller"} if {
	input.facts.reasonCode == "POSSIBLE_DUPLICATE"
	action in {"approve-variance", "short-pay"}
}

else := {"effect": "delegate", "to": "controller"} if amount > limit

# A price the buyer may have agreed, or an item substituted for the one they ordered, is the
# buyer's to accept: the one who placed the order.
else := {"effect": "delegate", "to": "buyer", "buyer": input.facts.buyer} if {
	action == "approve-variance"
	input.facts.reasonCode in {"PRICE_VARIANCE", "ITEM_SUBSTITUTED"}
	input.facts.buyer
}

else := {"effect": "delegate", "to": "ap-manager"}

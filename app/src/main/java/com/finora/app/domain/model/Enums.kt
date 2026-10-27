package com.finora.app.domain.model

enum class Direction { DEBIT, CREDIT }

/**
 * What a transaction IS, which decides whether it counts as income or expense. A debit is not
 * automatically an expense and a credit is not automatically income — see [countsAs].
 */
enum class TxnType(val label: String) {
    EXPENSE("Expense"),
    INCOME("Income"),
    SALARY("Salary"),
    SELF_TRANSFER("Own-account transfer"),
    CARD_BILL_PAYMENT("Credit card bill payment"),
    LOAN_DISBURSEMENT("Loan disbursement"),
    LOAN_REPAYMENT("Loan repayment"),
    LENT("Money lent"),
    LENDING_REPAYMENT_RECEIVED("Repayment received"),
    BORROWED("Money borrowed"),
    BORROWING_REPAID("Repaid borrowing"),
    REFUND("Refund"),
    REVERSAL("Reversal"),
    INTEREST_EARNED("Interest earned"),
    INTEREST_PAID("Interest paid"),
    BANK_FEE("Bank fee"),
    TAX("Tax"),
    ATM_WITHDRAWAL("ATM withdrawal"),
    CASH_DEPOSIT("Cash deposit"),
    INVESTMENT_PURCHASE("Investment purchase"),
    INVESTMENT_REDEMPTION("Investment withdrawal"),
    BILL_SETTLEMENT("Shared-bill settlement"),
    PERSON_PAYMENT("Payment to a person"),
    PERSON_RECEIPT("Money from a person"),
    ;

    /** How this type enters income/expense totals. */
    val countsAs: Flow
        get() = when (this) {
            EXPENSE, BANK_FEE, TAX, INTEREST_PAID -> Flow.EXPENSE
            INCOME, SALARY, INTEREST_EARNED -> Flow.INCOME
            REFUND -> Flow.EXPENSE_REDUCTION
            // Transfers, principal, lending, cash moves, investments, settlements, reversals and
            // unresolved person payments never inflate income or expense.
            else -> Flow.NEUTRAL
        }

    enum class Flow { INCOME, EXPENSE, EXPENSE_REDUCTION, NEUTRAL }
}

enum class PaymentMethod(val label: String) {
    UPI("UPI"), CARD("Card"), NETBANKING("Net banking"), NEFT("NEFT"), IMPS("IMPS"), RTGS("RTGS"),
    ATM("ATM"), CASH("Cash"), CHEQUE("Cheque"), AUTO_DEBIT("Auto-debit"), OTHER("Other")
}

enum class TxnSource { SMS, MANUAL, CSV_IMPORT, RECEIPT }

enum class ReviewStatus { NONE, NEEDS_REVIEW, RESOLVED }

/** Why a transaction is in the review queue. Drives grouping on the review screen. */
enum class ReviewReason(val label: String) {
    NEW_MERCHANT("New merchant"),
    CONFIRM_MERCHANT("Confirm merchant"),
    AMBIGUOUS_MERCHANT("Which business?"),
    UNKNOWN_RECIPIENT("Who was this?"),
    PURPOSE("What was this for?"),
    CLASSIFICATION("Check the type"),
}

enum class AccountType(val label: String) { BANK("Bank account"), CREDIT_CARD("Credit card"), WALLET("Wallet"), CASH("Cash") }

enum class CategoryKind { EXPENSE, INCOME }

enum class MerchantStatus { LEARNING, LEARNED, AMBIGUOUS }

enum class ConfirmationKind { CONFIRMED, CORRECTED, EDITED, RESET }

enum class LoanKind(val label: String) {
    LENT("Money I lent"), BORROWED("Money I borrowed"), FORMAL_LOAN("Formal loan")
}

enum class InterestMethod(val label: String) { NONE("No interest"), SIMPLE("Simple interest"), COMPOUND("Compound interest") }

enum class CompoundingFrequency(val label: String, val periodsPerYear: Int) {
    MONTHLY("Monthly", 12), QUARTERLY("Quarterly", 4), HALF_YEARLY("Half-yearly", 2), YEARLY("Yearly", 1)
}

enum class LoanPaymentKind(val label: String) {
    REPAYMENT("Repayment"), ADDITIONAL_DISBURSEMENT("Additional amount"), INTEREST_ONLY("Interest only"), WAIVER("Waived")
}

enum class LoanStatus { ACTIVE, CLOSED }

enum class InvestmentType(val label: String) {
    STOCK("Stock"), MUTUAL_FUND("Mutual fund"), ETF("ETF"), OTHER("Other")
}

enum class InvestmentTxnKind(val label: String) {
    BUY("Buy"), SIP("SIP instalment"), SELL("Sell"), WITHDRAWAL("Redemption"),
    DIVIDEND("Dividend / distribution"), SPLIT("Split"), BONUS("Bonus units"), UNIT_ADJUSTMENT("Unit adjustment")
}

enum class DocumentKind(val label: String) {
    RECEIPT("Receipt"), LOAN_AGREEMENT("Loan agreement"), INVESTMENT_STATEMENT("Investment statement"),
    BILL("Bill"), WARRANTY("Warranty"), OTHER("Other")
}

enum class LinkTarget { TRANSACTION, PERSON, LOAN, BILL_SPLIT, INVESTMENT }

enum class SplitMode(val label: String) { ITEMIZED("By item"), EQUAL("Equally"), CUSTOM("Custom amounts") }

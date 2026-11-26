package ai;

import model.NumberText;
import model.RiskFlag;
import service.ItemAnalysis;
import service.ReadService;

/**
 * The rule-based summary of the café's position, written entirely in Java.
 *
 * <p><strong>This is not AI output and is never presented as such.</strong> It restates the
 * ranking and arithmetic that {@code DashboardService} already computed as two or three
 * sentences. The API keeps it in its own field, labelled {@code "source":"RULES"}, so the
 * page cannot show it under an "AI advisor" heading by accident: the label travels with the
 * text rather than being decided by whoever renders it.</p>
 *
 * <p>It exists for two reasons. It is the honest answer when the model is unavailable, and
 * it is what the user gets instead when the model returns something unusable. In both cases
 * the numbers are the same ones the dashboard shows, correctly attributed.</p>
 *
 * <p>Order of business, as agreed: what will run out first, what will be wasted next, then
 * what has already been wasted - the order {@code DashboardService.flags()} produces.</p>
 */
public final class RuleSummary {

    /** How many warnings to name before summarising the rest as "and N more". */
    private static final int NAMED_LIMIT = 3;

    private RuleSummary() {
    }

    /**
     * Writes the summary for the current state of the café.
     *
     * @param read the read model, already loaded; nothing is modified here
     * @return two or three sentences, never empty, so the page always has something to show
     */
    public static String summarise(ReadService read) {
        RiskFlag[] flags = read.flags();
        StringBuilder text = new StringBuilder();

        if (flags.length == 0) {
            text.append("Nothing is flagged today: no item is about to run out, and no")
                    .append(" batch is at risk of expiring before it is sold.");
            return text.toString();
        }

        text.append("Today there ")
                .append(flags.length == 1 ? "is 1 warning" : "are " + flags.length + " warnings")
                .append(": ")
                .append(listOfCounts(flags))
                .append(". ")
                .append("Most urgent is ")
                .append(itemName(read, flags[0].getItemId()))
                .append(", ")
                .append(lowerFirst(flags[0].getReason()));

        double valueAtRisk = valueAtRisk(read.analyses());
        if (valueAtRisk > 0) {
            text.append(" The stock involved is worth RM")
                    .append(NumberText.money(valueAtRisk))
                    .append(" at cost.");
        }
        return text.toString();
    }

    /** "3 items about to run out, 2 batches at risk of expiring and 1 already expired". */
    private static String listOfCounts(RiskFlag[] flags) {
        int[] counts = {
                countOfType(flags, RiskFlag.TYPE_STOCKOUT),
                countOfType(flags, RiskFlag.TYPE_EXPIRY),
                countOfType(flags, RiskFlag.TYPE_EXPIRED)
        };
        String[] one = {"item about to run out", "batch at risk of expiring before it sells",
                "batch already expired"};
        String[] many = {"items about to run out", "batches at risk of expiring before they sell",
                "batches already expired"};

        int groups = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) {
                groups++;
            }
        }

        StringBuilder list = new StringBuilder();
        int placed = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) {
                continue;
            }
            placed++;
            if (placed > 1) {
                // "and" before the last group, a comma before the others.
                list.append(placed == groups ? " and " : ", ");
            }
            list.append(counts[i] == 1 ? "1 " + one[i] : counts[i] + " " + many[i]);
        }
        return list.toString();
    }

    /**
     * Value at cost of the stock any warning attaches to.
     *
     * <p>Deliberately a range of concern, not a forecast of loss: the same stock might be
     * sold or might be wasted, and the dashboard's job is to say which items need attention
     * rather than to predict the outcome.</p>
     */
    private static double valueAtRisk(ItemAnalysis[] analyses) {
        double total = 0;
        for (int i = 0; i < analyses.length; i++) {
            ItemAnalysis analysis = analyses[i];
            boolean hasExpiryRisk = !analysis.noExpiry()
                    && analysis.daysUntilExpiry <= analysis.item.getShelfLifeDays();
            if (analysis.stockoutRisk || hasExpiryRisk) {
                total += analysis.stockValueAtCost;
            }
        }
        return total;
    }

    private static int countOfType(RiskFlag[] flags, String type) {
        int count = 0;
        for (int i = 0; i < flags.length; i++) {
            if (flags[i].getType().equals(type)) {
                count++;
            }
        }
        return count;
    }

    private static String itemName(ReadService read, String itemId) {
        try {
            return read.requireItem(itemId).getName();
        } catch (Exception e) {
            return itemId;
        }
    }

    /** "Fresh Milk 1L is low" reads better than "Fresh Milk 1L Is low". */
    private static String lowerFirst(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    /** Exposed so the page can say "and N more" consistently with what is named here. */
    public static int namedLimit() {
        return NAMED_LIMIT;
    }
}

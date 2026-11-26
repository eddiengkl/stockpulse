// Manual check program - NOT part of the StockPulse website.
//
// It lives outside src/ so the submission source stays limited to the website itself.
// Compile and run it against the compiled model classes:
//
//   javac -d out -sourcepath src tools/check/ModelCheck.java
//   java -cp out ModelCheck
//
// It prints the model's behaviour for eyeballing and deliberately triggers the invalid
// cases so each rejection message can be read. Delete this file once the model is
// reviewed.

import java.time.LocalDate;

import exception.InvalidSeasonException;
import model.Batch;
import model.DataText;
import model.DateText;
import model.LongLifeItem;
import model.NumberText;
import model.PerishableItem;
import model.Season;
import model.Settings;
import model.StockItem;

public final class ModelCheck {

    private ModelCheck() {
    }

    public static void main(String[] args) {
        System.out.println("=== StockPulse model check ===");

        // 1. Two subclasses in one parent array: the hierarchy.
        StockItem[] items = new StockItem[3];
        int itemCount = 0;
        items[itemCount++] = new PerishableItem("I001", "Fresh Milk 1L", "Dairy", "L",
                4.50, 2.10, 7, 12, "Creamery Sdn Bhd", 2);
        items[itemCount++] = new PerishableItem("I004", "Butter Croissant", "Bakery", "pcs",
                3.80, 1.40, 2, 20, "Golden Crust", 1);
        items[itemCount++] = new LongLifeItem("I014", "Iced Syrup - Lychee", "Syrup", "bottle",
                18.00, 9.00, 365, 6, "Sweetline", 5);

        LocalDate today = LocalDate.of(2026, 11, 26);

        // 2. Seasons: wrapping, single month, and a per-item multiplier.
        try {
            Season dry = new Season("Dry", 5, 9);
            Season rainy = new Season("Rainy", 10, 2);
            Season festive = new Season("Festive", 12, 12);
            System.out.println("Seasons: " + dry.describe() + " | " + rainy.describe()
                    + " | " + festive.describe() + " (months=" + festive.monthCount() + ")");
            System.out.println("Rainy covers Nov/Jan/Jun: " + rainy.covers(11) + "/"
                    + rainy.covers(1) + "/" + rainy.covers(6));
            System.out.println("Festive covers Dec/Jan:  " + festive.covers(12) + "/"
                    + festive.covers(1));

            StockItem syrup = items[2];
            syrup.setSeason(rainy);
            syrup.setSeasonalMultiplier(1.35);
            System.out.println("Syrup multiplier in Rainy/Dry: "
                    + syrup.seasonalMultiplierFor(rainy) + "/" + syrup.seasonalMultiplierFor(dry));

            // 3. Batches with different expiry dates.
            Batch b1 = new Batch("B0039", "I001", 24, 6, today.minusDays(6), today.plusDays(3), 2.10);
            Batch b2 = new Batch("B0041", "I001", 24, 12, today.minusDays(2), today.plusDays(9), 2.10);
            long[] milkExpiries = {b1.getExpiryDate().toEpochDay(), b2.getExpiryDate().toEpochDay()};

            // 4. Polymorphism: same calls, different answers, no instanceof.
            System.out.println();
            System.out.println("--- Polymorphic calls on one array ---");
            for (int i = 0; i < itemCount; i++) {
                StockItem item = items[i];
                boolean isMilk = item.getItemId().equals("I001");
                int daysLeft = item.daysUntilExpiry(isMilk ? milkExpiries : new long[0], today);
                System.out.printf("%-6s %-10s canExpire=%-5s daysLeft=%-6s %s%n",
                        item.getItemId(), item.itemType(), item.canExpire(),
                        daysLeft == Integer.MAX_VALUE ? "never" : daysLeft,
                        item.describeStock(isMilk ? 18 : 40, 2, daysLeft));
            }
            System.out.println("Discount: milk says '" + StockItem.discountText(items[0].discountPercentFor(2))
                    + "', syrup says '" + StockItem.discountText(items[2].discountPercentFor(2))
                    + "' (no fixed percentage rule in the MVP)");

            // 5. FEFO draw.
            System.out.println();
            System.out.println("--- First-expired-first-out draw of 10 L ---");
            double remaining = 10;
            double fromB1 = b1.take(remaining);
            remaining -= fromB1;
            double fromB2 = b2.take(remaining);
            System.out.println("Took " + NumberText.quantity(fromB1) + " from B0039 and "
                    + NumberText.quantity(fromB2) + " from B0041 -> B0039="
                    + NumberText.quantity(b1.getQtyRemaining()) + " B0041="
                    + NumberText.quantity(b2.getQtyRemaining()));

            // 6. Data lines.
            System.out.println();
            System.out.println("--- Data lines ---");
            System.out.println(items[0].toDataLine());
            System.out.println(items[2].toDataLine());
            System.out.println(b2.toDataLine());
            System.out.println("Parsed back: " + DateText.parse("26/11/2026"));

            // 7. Settings and season resolution.
            Settings settings = new Settings();
            settings.setDemoDate(LocalDate.of(2026, 7, 15));
            Season[] seasonArray = {dry, rainy};
            settings.setSeasonFromDate(true);
            System.out.println();
            System.out.println("Demo date " + DateText.format(settings.effectiveDate())
                    + ", season derived from date = "
                    + settings.resolveActiveSeason(seasonArray, 2).describe());

        } catch (InvalidSeasonException e) {
            System.out.println("[caught InvalidSeasonException] " + e.getMessage());
        }

        // 8. Invalid values must be rejected, not stored.
        System.out.println();
        System.out.println("--- Rejection checks ---");
        expectSeasonRejected("Dry", 13, 2);
        expectSeasonRejected("Dry", 0, 4);
        expectSeasonRejected("  ", 5, 9);
        expectSeasonRejected("Dr|y", 5, 9);

        expectRejected("negative received qty", () ->
                new Batch("B1", "I001", -5, 0, LocalDate.of(2026, 11, 1), null, 1.0));
        expectRejected("zero received qty", () ->
                new Batch("B1", "I001", 0, 0, LocalDate.of(2026, 11, 1), null, 1.0));
        expectRejected("remaining > received", () ->
                new Batch("B1", "I001", 10, 11, LocalDate.of(2026, 11, 1), null, 1.0));
        expectRejected("negative remaining", () ->
                new Batch("B1", "I001", 10, -1, LocalDate.of(2026, 11, 1), null, 1.0));
        expectRejected("negative cost", () ->
                new Batch("B1", "I001", 10, 5, LocalDate.of(2026, 11, 1), null, -0.5));
        expectRejected("expiry before received", () ->
                new Batch("B1", "I001", 10, 5, LocalDate.of(2026, 11, 10),
                        LocalDate.of(2026, 11, 1), 1.0));
        expectRejected("take(-4) would add stock", () -> {
            Batch fresh = new Batch("B9", "I001", 10, 10, LocalDate.of(2026, 11, 1), null, 1.0);
            fresh.take(-4);
            return null;
        });
        expectRejected("lowering received below remaining", () -> {
            Batch held = new Batch("B8", "I001", 10, 8, LocalDate.of(2026, 11, 1), null, 1.0);
            held.setQtyReceived(4);
            return null;
        });

        expectRejected("item name with '|'", () -> new PerishableItem(
                "I900", "Milk | promo", "Dairy", "L", 4.0, 2.0, 7, 12, "Creamery", 2));
        expectRejected("item name with line break", () -> new PerishableItem(
                "I901", "Milk\n2L", "Dairy", "L", 4.0, 2.0, 7, 12, "Creamery", 2));
        expectRejected("negative price", () -> new PerishableItem(
                "I902", "Milk", "Dairy", "L", -4.0, 2.0, 7, 12, "Creamery", 2));
        expectRejected("pack size 0", () -> new PerishableItem(
                "I903", "Milk", "Dairy", "L", 4.0, 2.0, 7, 0, "Creamery", 2));
        expectRejected("blank supplier", () -> new PerishableItem(
                "I904", "Milk", "Dairy", "L", 4.0, 2.0, 7, 12, "   ", 2));
        expectRejected("negative shelf life", () -> new PerishableItem(
                "I905", "Milk", "Dairy", "L", 4.0, 2.0, -1, 12, "Creamery", 2));

        System.out.println();
        System.out.println("Commas must still be allowed, only '|' and line breaks are unsafe.");
        System.out.println("      accepted as expected: '" + DataText.safeField("Milk, fresh", "item name") + "'");

        System.out.println();
        System.out.println("=== Model check finished ===");
    }

    private static void expectSeasonRejected(String name, int start, int end) {
        try {
            new Season(name, start, end);
            System.out.println("UNEXPECTED: season " + name + " " + start + "-" + end + " accepted");
        } catch (InvalidSeasonException e) {
            System.out.println("caught InvalidSeasonException: " + e.getMessage());
        }
    }

    private static void expectRejected(String label, CheckedCall call) {
        try {
            call.run();
            System.out.println("NOT REJECTED        : " + label);
        } catch (RuntimeException e) {
            System.out.println("rejected as expected: " + label + "\n      -> " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface CheckedCall {
        Object run();
    }
}

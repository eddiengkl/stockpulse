package service;

import java.time.LocalDate;

import model.Batch;
import model.StockItem;
import store.BatchStore;

/**
 * Moves stock out of batches and sales out of stock, applying the first-expired,
 * first-out rule.
 *
 * <p>Kept separate from {@link InventoryService} so the allocation rule has one home
 * and can be read on its own: all users of stock (sales, waste) ask this class which
 * batch to take from.</p>
 */
public class StockAllocator {

    private final BatchStore batches;

    public StockAllocator(BatchStore batches) {
        this.batches = batches;
    }

    /**
     * Batches of an item that can be sold today, in the order they should be sold:
     * soonest expiry first, expired batches excluded, batches without an expiry last.
     *
     * @return an array in sale order; length 0 when nothing usable is in stock
     */
    public Batch[] saleableBatches(String itemId, LocalDate today) {
        int[] indexes = batches.indexesForItem(itemId);
        Batch[] order = new Batch[indexes.length];
        int n = 0;
        for (int i = 0; i < indexes.length; i++) {
            Batch batch = batches.batches()[indexes[i]];
            if (!batch.isDepleted() && !batch.isExpired(today)) {
                order[n++] = batch;
            }
        }
        // Trim by hand rather than Arrays.copyOf, so the source uses no helper from
        // java.util at all.
        Batch[] trimmed = new Batch[n];
        for (int i = 0; i < n; i++) {
            trimmed[i] = order[i];
        }
        // Insertion sort: n is at most a handful of batches per item.
        for (int i = 1; i < trimmed.length; i++) {
            Batch key = trimmed[i];
            int j = i - 1;
            while (j >= 0 && sortKey(trimmed[j]) > sortKey(key)) {
                trimmed[j + 1] = trimmed[j];
                j--;
            }
            trimmed[j + 1] = key;
        }
        return trimmed;
    }

    /**
     * Total sellable quantity for an item, ignoring expired batches.
     */
    public double saleableStock(String itemId, LocalDate today) {
        Batch[] order = saleableBatches(itemId, today);
        double total = 0;
        for (int i = 0; i < order.length; i++) {
            total += order[i].getQtyRemaining();
        }
        return total;
    }

    /**
     * True when the item holds stock but every remaining batch is expired, which is the
     * case worth reporting separately from "out of stock".
     */
    public boolean allStockExpired(String itemId, LocalDate today) {
        int[] indexes = batches.indexesForItem(itemId);
        boolean anyStock = false;
        for (int i = 0; i < indexes.length; i++) {
            Batch batch = batches.batches()[indexes[i]];
            if (!batch.isDepleted()) {
                anyStock = true;
                if (!batch.isExpired(today)) {
                    return false;
                }
            }
        }
        return anyStock;
    }

    /**
     * Describes which batches <em>would</em> supply a quantity, without changing any
     * stock. Useful for previews and for building a record before committing to it.
     */
    public String previewAllocation(String itemId, double quantity, LocalDate today) {
        Batch[] order = saleableBatches(itemId, today);
        double remaining = quantity;
        StringBuilder draws = new StringBuilder();
        for (int i = 0; i < order.length && remaining > 0.0000001; i++) {
            double taken = Math.min(remaining, order[i].getQtyRemaining());
            if (taken > 0.0000001) {
                if (draws.length() > 0) {
                    draws.append(',');
                }
                draws.append(order[i].getBatchId()).append(':').append(trim(taken));
                remaining -= taken;
            }
        }
        return draws.toString();
    }

    /**
     * Takes {@code quantity} out of an item's batches, first-expired first, and
     * describes what was taken.
     *
     * <p>Callers must check availability first and throw
     * {@code InsufficientStockException}; this method assumes the quantity fits and
     * throws {@link IllegalArgumentException} if it does not, because reaching that
     * point would be a programming error.</p>
     *
     * @return a description like "B0039:6,B0041:4"
     */
    public String allocate(String itemId, double quantity, LocalDate today) {
        Batch[] order = saleableBatches(itemId, today);
        double remaining = quantity;
        StringBuilder draws = new StringBuilder();
        for (int i = 0; i < order.length && remaining > 0.0000001; i++) {
            double taken = order[i].take(remaining);
            if (taken > 0.0000001) {
                if (draws.length() > 0) {
                    draws.append(',');
                }
                draws.append(order[i].getBatchId()).append(':').append(trim(taken));
                remaining -= taken;
            }
        }
        if (remaining > 0.0000001) {
            throw new IllegalArgumentException(
                    "Allocation of " + trim(quantity) + " for " + itemId
                            + " left " + trim(remaining) + " unallocated; check stock first.");
        }
        return draws.toString();
    }

    /**
     * Puts stock back, newest batch first, for an undo. Used only by the undo action so
     * a mistaken sale does not silently vanish.
     */
    public void returnToBatches(String itemId, String draws, LocalDate today) {
        if (draws == null || draws.trim().isEmpty()) {
            return;
        }
        String[] parts = draws.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].trim();
            int colon = part.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String batchId = part.substring(0, colon).trim();
            double qty = Double.parseDouble(part.substring(colon + 1).trim());
            Batch batch = batches.findById(batchId);
            if (batch != null) {
                batch.setQtyRemaining(batch.getQtyRemaining() + qty);
            }
        }
    }

    /** Soonest expiry first; batches with no expiry date sort last. */
    private static long sortKey(Batch batch) {
        return (batch.getExpiryDate() == null) ? Long.MAX_VALUE : batch.getExpiryDate().toEpochDay();
    }

    /** Soonest expiry first for an item, used by the item screen. */
    public Batch[] batchesInExpiryOrder(String itemId) {
        int[] indexes = batches.indexesForItem(itemId);
        Batch[] order = new Batch[indexes.length];
        for (int i = 0; i < indexes.length; i++) {
            order[i] = batches.batches()[indexes[i]];
        }
        for (int i = 1; i < order.length; i++) {
            Batch key = order[i];
            int j = i - 1;
            while (j >= 0 && sortKey(order[j]) > sortKey(key)) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = key;
        }
        return order;
    }

    /** Item name helper kept here so callers do not need the item store. */
    public static String describeItem(StockItem item) {
        return (item == null) ? "unknown item" : item.getName();
    }

    private static String trim(double value) {
        long whole = (long) value;
        return (whole == value) ? String.valueOf(whole) : String.valueOf(value);
    }
}

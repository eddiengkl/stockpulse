package service;

import java.time.LocalDate;

import exception.ExpiredStockException;
import exception.InsufficientStockException;
import exception.InvalidInputException;
import exception.ItemNotFoundException;
import exception.RoleNotPermittedException;
import exception.StorageFullException;
import model.ActivityLog;
import model.Batch;
import model.SaleRecord;
import model.Settings;
import model.StockItem;
import model.WasteRecord;
import store.BatchStore;
import store.ItemStore;
import store.SaleStore;
import store.WasteStore;

/**
 * The business rules for changing stock, all in Java.
 *
 * <p>This is the only class allowed to move stock. It checks the rules, throws the
 * custom exceptions when they are broken, and writes an activity line for every change
 * so the demo can show that the work happened here and not in the browser.</p>
 *
 * <p>Roles are <em>demo role gating</em>, not authentication: the role comes from a
 * picker on the page, and it is enforced here in Java rather than by hiding buttons.</p>
 */
public class InventoryService {

    public static final String ROLE_MANAGER = "Manager";
    public static final String ROLE_STAFF = "Staff";

    private final ItemStore items;
    private final BatchStore batches;
    private final SaleStore sales;
    private final WasteStore waste;
    private final Settings settings;
    private final StockAllocator allocator;
    private final RiskEngine risk;
    private final ActivityLogStore activity;

    public InventoryService(ItemStore items, BatchStore batches, SaleStore sales,
                            WasteStore waste, Settings settings, ActivityLogStore activity) {
        this.items = items;
        this.batches = batches;
        this.sales = sales;
        this.waste = waste;
        this.settings = settings;
        this.activity = activity;
        this.allocator = new StockAllocator(batches);
        this.risk = new RiskEngine(items::findById, batches, sales, settings);
    }

    // ------------------------------------------------------------------ wiring

    /** Gives the risk engine the seasons it needs for seasonal velocity. */
    public void useSeasons(model.Season[] seasons, int count) {
        risk.useSeasons(seasons, count);
    }

    public RiskEngine risk() {
        return risk;
    }

    public StockAllocator allocator() {
        return allocator;
    }

    // ------------------------------------------------------------------- sales

    /**
     * Records a sale: checks stock and expiry, takes the units first-expired first, and
     * writes the sale and an activity line only after every check has passed.
     *
     * @param itemId    item being sold
     * @param quantity  units sold, must be &gt; 0
     * @param unitPrice price charged, or {@code Double.NaN} to use the item's own price
     * @param role      demo role, "Manager" or "Staff"
     * @return the stored sale record
     * @throws ItemNotFoundException      when the item id is unknown
     * @throws InvalidInputException      when the quantity or price is unusable
     * @throws InsufficientStockException when the item does not hold enough sellable stock
     * @throws StorageFullException       when the sales ledger is full
     */
    public SaleRecord recordSale(String itemId, double quantity, double unitPrice, String role)
            throws ItemNotFoundException, InvalidInputException, InsufficientStockException,
                   StorageFullException, ExpiredStockException, RoleNotPermittedException {
        requireRole(role, ROLE_STAFF, "record a sale");
        StockItem item = requireItem(itemId);
        checkQuantity(quantity, "qty");

        LocalDate today = settings.effectiveDate();
        double sellable = allocator.saleableStock(item.getItemId(), today);
        if (sellable <= 0 && allocator.allStockExpired(item.getItemId(), today)) {
            Batch[] all = allocator.batchesInExpiryOrder(item.getItemId());
            Batch expired = null;
            for (int i = 0; i < all.length; i++) {
                if (!all[i].isDepleted() && all[i].isExpired(today)) {
                    expired = all[i];
                }
            }
            if (expired != null) {
                throw new ExpiredStockException(expired.getBatchId(), item.getName(),
                        -expired.daysLeft(today));
            }
        }
        if (quantity > sellable + 0.0000001) {
            throw new InsufficientStockException(item.getName(), quantity, sellable);
        }
        if (sales.isFull()) {
            throw new StorageFullException("sales", sales.capacity());
        }

        double price = Double.isNaN(unitPrice) ? item.getSellingPrice() : unitPrice;
        if (!Double.isFinite(price) || price < 0) {
            throw new InvalidInputException("unitPrice",
                    "Price must be a normal number of 0 or more.");
        }

        String draws = allocator.allocate(item.getItemId(), quantity, today);
        SaleRecord sale;
        try {
            sale = new SaleRecord(sales.nextSaleId(), item.getItemId(), today,
                    quantity, price, draws);
        } catch (IllegalArgumentException e) {
            // Stock was already drawn, so put it straight back before reporting.
            allocator.returnToBatches(item.getItemId(), draws, today);
            throw new InvalidInputException("sale", e.getMessage(), e);
        }
        sales.add(sale);

        activity.add(role, "SALE", "Sold " + item.describeQuantity(quantity) + " of "
                + item.getName() + " from " + draws);
        return sale;
    }

    /**
     * Undoes the most recent sale recorded today for an item, returning the stock to
     * the batches it came from. Manager only.
     *
     * @return true when a sale was undone, false when there was nothing to undo
     */
    public boolean undoLastSale(String itemId, String role)
            throws ItemNotFoundException, RoleNotPermittedException {
        requireRole(role, ROLE_MANAGER, "undo a sale");
        StockItem item = requireItem(itemId);
        LocalDate today = settings.effectiveDate();
        SaleRecord removed = sales.removeMostRecentForItem(item.getItemId(), today);
        if (removed == null) {
            return false;
        }
        allocator.returnToBatches(item.getItemId(), removed.getBatchDraws(), today);
        activity.add(role, "UNDO", "Undid sale " + removed.getSaleId() + " of "
                + item.getName());
        return true;
    }

    // --------------------------------------------------------------- deliveries

    /**
     * Receives a delivery and creates a batch for it.
     *
     * @param expiryDate required for perishable items, ignored for long-life stock
     * @throws ItemNotFoundException when the item id is unknown
     * @throws InvalidInputException when a quantity or date is unusable
     * @throws StorageFullException  when the batch table is full
     */
    public Batch receiveDelivery(String itemId, double quantity, LocalDate expiryDate,
                                 double unitCost, String role)
            throws ItemNotFoundException, InvalidInputException, StorageFullException,
                   RoleNotPermittedException {
        requireRole(role, ROLE_STAFF, "receive a delivery");
        StockItem item = requireItem(itemId);
        checkQuantity(quantity, "qty");
        if (batches.isFull()) {
            throw new StorageFullException("batch", batches.capacity());
        }
        double cost = (unitCost < 0) ? Double.NaN : unitCost;
        if (Double.isNaN(cost)) {
            throw new InvalidInputException("unitCost", "Unit cost cannot be negative.");
        }
        LocalDate today = settings.effectiveDate();
        LocalDate useExpiry = item.canExpire() ? expiryDate : null;
        if (item.canExpire() && useExpiry == null) {
            throw new InvalidInputException("expiryDate",
                    "An expiry date is required for " + item.getName() + ".");
        }
        if (useExpiry != null && useExpiry.isBefore(today)) {
            throw new InvalidInputException("expiryDate",
                    "Expiry date is already in the past; that delivery would arrive expired.");
        }

        Batch batch;
        try {
            batch = new Batch(batches.nextBatchId(), item.getItemId(), quantity, quantity,
                    today, useExpiry, cost);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("delivery", e.getMessage(), e);
        }
        batches.add(batch);
        activity.add(role, "DELIVERY", "Received " + item.describeQuantity(quantity)
                + " of " + item.getName() + " as " + batch.getBatchId());
        return batch;
    }

    // -------------------------------------------------------------------- waste

    /**
     * Records stock thrown away, normally expired stock. The quantity may not exceed the
     * batch, and the batch must belong to the item.
     *
     * <p>Every check, including building the record itself, happens before the batch is
     * touched. An earlier version drew the stock down first, so a reason containing an
     * illegal character could fail after the quantity was already gone.</p>
     */
    public WasteRecord recordWaste(String itemId, String batchId, double quantity, String reason,
                                   String role)
            throws ItemNotFoundException, InvalidInputException, InsufficientStockException,
                   StorageFullException, RoleNotPermittedException {
        requireRole(role, ROLE_STAFF, "record waste");
        StockItem item = requireItem(itemId);
        checkQuantity(quantity, "qty");
        Batch batch = batches.findById(batchId);
        if (batch == null || !batch.getItemId().equalsIgnoreCase(item.getItemId())) {
            throw new ItemNotFoundException(String.valueOf(batchId), "Batch");
        }
        if (quantity > batch.getQtyRemaining() + 0.0000001) {
            throw new InsufficientStockException("batch " + batch.getBatchId(),
                    quantity, batch.getQtyRemaining());
        }
        if (waste.isFull()) {
            throw new StorageFullException("waste", waste.capacity());
        }

        // Construct first: a rejected reason must not have reduced the batch already.
        WasteRecord record;
        try {
            record = new WasteRecord(waste.nextWasteId(), batch.getBatchId(),
                    item.getItemId(), settings.effectiveDate(), quantity, batch.getUnitCost(), reason);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("reason", e.getMessage(), e);
        }

        batch.take(quantity);
        waste.add(record);
        activity.add(role, "WASTE", "Wrote off " + item.describeQuantity(quantity)
                + " of " + item.getName() + " from " + batch.getBatchId());
        return record;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Resolves an item id or throws.
     *
     * @throws ItemNotFoundException when no item has that id
     */
    public StockItem requireItem(String itemId) throws ItemNotFoundException {
        StockItem item = items.findById(itemId);
        if (item == null) {
            throw new ItemNotFoundException(String.valueOf(itemId), "Item");
        }
        return item;
    }

    /**
     * Demo role gating: Manager may do anything, Staff may do everything except
     * manager-only actions. The action is named so the refusal reads properly and the
     * API can tell the page which control to disable.
     *
     * @throws RoleNotPermittedException when the role is unknown or too low
     */
    private static void requireRole(String role, String minimumRole, String action)
            throws RoleNotPermittedException {
        if (role == null) {
            throw new RoleNotPermittedException("unknown", action);
        }
        if (ROLE_MANAGER.equalsIgnoreCase(role.trim())) {
            return;
        }
        if (ROLE_STAFF.equalsIgnoreCase(role.trim()) && ROLE_STAFF.equals(minimumRole)) {
            return;
        }
        throw new RoleNotPermittedException(role.trim(), action);
    }

    private static void checkQuantity(double quantity, String field) throws InvalidInputException {
        if (Double.isNaN(quantity) || Double.isInfinite(quantity)) {
            throw new InvalidInputException(field, "Quantity must be a number.");
        }
        if (quantity <= 0) {
            throw new InvalidInputException(field, "Quantity must be more than 0.");
        }
    }

    public ItemStore items() { return items; }
    public BatchStore batches() { return batches; }
    public SaleStore sales() { return sales; }
    public WasteStore waste() { return waste; }
    public Settings settings() { return settings; }
    public ActivityLogStore activityLog() { return activity; }
}

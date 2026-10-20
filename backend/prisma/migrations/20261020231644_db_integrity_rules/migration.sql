-- =============================================================================
-- Dejavu — database-level integrity rules that Prisma cannot express
-- (CHECK constraints, partial unique indexes, double-entry triggers, views).
-- =============================================================================

-- ---------- Generic CHECK constraints ---------------------------------------

DO $$
BEGIN
  -- Journal lines: non-negative, exactly one side populated.
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'journal_lines_one_side_chk') THEN
    ALTER TABLE journal_lines ADD CONSTRAINT journal_lines_one_side_chk
      CHECK (debit >= 0 AND credit >= 0 AND (debit = 0) <> (credit = 0));
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'stock_levels_non_negative_chk') THEN
    ALTER TABLE stock_levels ADD CONSTRAINT stock_levels_non_negative_chk
      CHECK (on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand);
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'cart_items_qty_chk') THEN
    ALTER TABLE cart_items ADD CONSTRAINT cart_items_qty_chk CHECK (quantity > 0);
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'order_items_qty_chk') THEN
    ALTER TABLE order_items ADD CONSTRAINT order_items_qty_chk
      CHECK (quantity > 0 AND returned_qty >= 0 AND returned_qty <= quantity);
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'purchase_order_items_qty_chk') THEN
    ALTER TABLE purchase_order_items ADD CONSTRAINT purchase_order_items_qty_chk
      CHECK (quantity > 0 AND received_qty >= 0 AND received_qty <= quantity);
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'orders_amounts_chk') THEN
    ALTER TABLE orders ADD CONSTRAINT orders_amounts_chk
      CHECK (subtotal >= 0 AND discount_total >= 0 AND shipping_fee >= 0
             AND tax_total >= 0 AND grand_total >= 0 AND amount_paid >= 0);
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'product_reviews_rating_chk') THEN
    ALTER TABLE product_reviews ADD CONSTRAINT product_reviews_rating_chk
      CHECK (rating BETWEEN 1 AND 5);
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'devices_owner_chk') THEN
    ALTER TABLE devices ADD CONSTRAINT devices_owner_chk
      CHECK ((user_id IS NULL) <> (customer_id IS NULL));
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'refresh_tokens_owner_chk') THEN
    ALTER TABLE refresh_tokens ADD CONSTRAINT refresh_tokens_owner_chk
      CHECK ((user_id IS NULL) <> (customer_id IS NULL));
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fiscal_periods_dates_chk') THEN
    ALTER TABLE fiscal_periods ADD CONSTRAINT fiscal_periods_dates_chk
      CHECK (start_date <= end_date);
  END IF;
END $$;

-- Only one default address per customer / one default warehouse.
CREATE UNIQUE INDEX IF NOT EXISTS customer_addresses_one_default_idx
  ON customer_addresses (customer_id) WHERE is_default;
CREATE UNIQUE INDEX IF NOT EXISTS warehouses_one_default_idx
  ON warehouses ((true)) WHERE is_default;

-- ---------- Journal lines may only hit active, postable accounts -------------

CREATE OR REPLACE FUNCTION journal_line_account_guard() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM accounts WHERE id = NEW.account_id AND is_postable AND is_active
  ) THEN
    RAISE EXCEPTION 'Account % is not postable or inactive', NEW.account_id;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_journal_line_account_guard ON journal_lines;
CREATE TRIGGER trg_journal_line_account_guard
  BEFORE INSERT OR UPDATE OF account_id ON journal_lines
  FOR EACH ROW EXECUTE FUNCTION journal_line_account_guard();

-- ---------- Posted entries are immutable -------------------------------------

CREATE OR REPLACE FUNCTION journal_line_immutable_guard() RETURNS trigger AS $$
DECLARE
  v_entry uuid := COALESCE(NEW.journal_entry_id, OLD.journal_entry_id);
BEGIN
  IF EXISTS (SELECT 1 FROM journal_entries WHERE id = v_entry AND status <> 'DRAFT') THEN
    RAISE EXCEPTION 'Journal entry % is posted; create a reversal instead', v_entry;
  END IF;
  RETURN COALESCE(NEW, OLD);
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_journal_line_immutable_guard ON journal_lines;
CREATE TRIGGER trg_journal_line_immutable_guard
  BEFORE INSERT OR UPDATE OR DELETE ON journal_lines
  FOR EACH ROW EXECUTE FUNCTION journal_line_immutable_guard();

CREATE OR REPLACE FUNCTION journal_entry_status_guard() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.status <> 'DRAFT' THEN
      RAISE EXCEPTION 'Cannot delete posted journal entry %', OLD.number;
    END IF;
    RETURN OLD;
  END IF;

  -- Allowed transitions: DRAFT -> POSTED, POSTED -> REVERSED.
  IF OLD.status = 'POSTED' THEN
    IF NEW.status <> 'REVERSED'
       OR NEW.entry_date <> OLD.entry_date
       OR NEW.period_id <> OLD.period_id
       OR NEW.total_debit <> OLD.total_debit
       OR NEW.total_credit <> OLD.total_credit THEN
      RAISE EXCEPTION 'Posted journal entry % is immutable', OLD.number;
    END IF;
  ELSIF OLD.status = 'REVERSED' THEN
    RAISE EXCEPTION 'Reversed journal entry % is immutable', OLD.number;
  END IF;

  -- Posting: period must be open and the entry must balance.
  IF NEW.status = 'POSTED' AND OLD.status = 'DRAFT' THEN
    IF NOT EXISTS (
      SELECT 1 FROM fiscal_periods
      WHERE id = NEW.period_id AND status = 'OPEN'
        AND NEW.entry_date BETWEEN start_date AND end_date
    ) THEN
      RAISE EXCEPTION 'Entry date % is not inside an open fiscal period', NEW.entry_date;
    END IF;

    SELECT COALESCE(SUM(debit), 0), COALESCE(SUM(credit), 0)
      INTO NEW.total_debit, NEW.total_credit
      FROM journal_lines WHERE journal_entry_id = NEW.id;

    IF NEW.total_debit = 0 OR NEW.total_debit <> NEW.total_credit THEN
      RAISE EXCEPTION 'Journal entry % is unbalanced (debit %, credit %)',
        NEW.number, NEW.total_debit, NEW.total_credit;
    END IF;

    IF (SELECT COUNT(*) FROM journal_lines WHERE journal_entry_id = NEW.id) < 2 THEN
      RAISE EXCEPTION 'Journal entry % needs at least two lines', NEW.number;
    END IF;

    NEW.posted_at := COALESCE(NEW.posted_at, now());
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_journal_entry_status_guard ON journal_entries;
CREATE TRIGGER trg_journal_entry_status_guard
  BEFORE UPDATE OR DELETE ON journal_entries
  FOR EACH ROW EXECUTE FUNCTION journal_entry_status_guard();

-- Entries may not be inserted directly as POSTED (lines would not exist yet).
CREATE OR REPLACE FUNCTION journal_entry_insert_guard() RETURNS trigger AS $$
BEGIN
  IF NEW.status <> 'DRAFT' THEN
    RAISE EXCEPTION 'Insert journal entries as DRAFT, add lines, then post';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_journal_entry_insert_guard ON journal_entries;
CREATE TRIGGER trg_journal_entry_insert_guard
  BEFORE INSERT ON journal_entries
  FOR EACH ROW EXECUTE FUNCTION journal_entry_insert_guard();

-- ---------- Stock movements are append-only ----------------------------------

CREATE OR REPLACE FUNCTION stock_movement_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'stock_movements is append-only; post a correcting movement';
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_stock_movement_append_only ON stock_movements;
CREATE TRIGGER trg_stock_movement_append_only
  BEFORE UPDATE OR DELETE ON stock_movements
  FOR EACH ROW EXECUTE FUNCTION stock_movement_append_only();

-- ---------- Reporting views ---------------------------------------------------

-- Trial balance from posted entries only.
CREATE OR REPLACE VIEW v_trial_balance AS
SELECT a.id AS account_id, a.code, a.name_ar, a.name_en, a.type,
       COALESCE(SUM(l.debit), 0)  AS total_debit,
       COALESCE(SUM(l.credit), 0) AS total_credit,
       CASE WHEN a.normal_balance = 'DEBIT'
            THEN COALESCE(SUM(l.debit - l.credit), 0)
            ELSE COALESCE(SUM(l.credit - l.debit), 0) END AS balance
FROM accounts a
LEFT JOIN (
  journal_lines l
  JOIN journal_entries e ON e.id = l.journal_entry_id AND e.status IN ('POSTED', 'REVERSED')
) ON l.account_id = a.id
WHERE a.is_postable
GROUP BY a.id;

-- Available stock per variant across all warehouses.
CREATE OR REPLACE VIEW v_variant_availability AS
SELECT v.id AS variant_id, v.sku,
       COALESCE(SUM(s.on_hand), 0) AS on_hand,
       COALESCE(SUM(s.reserved), 0) AS reserved,
       COALESCE(SUM(s.on_hand - s.reserved), 0) AS available,
       COALESCE(SUM(s.on_hand), 0) <= v.low_stock_threshold AS is_low_stock
FROM product_variants v
LEFT JOIN stock_levels s ON s.variant_id = v.id
GROUP BY v.id;

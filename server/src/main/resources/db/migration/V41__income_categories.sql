-- Income categories: extend categories.kind with 'income' and let income rows carry category_id.

ALTER TABLE categories DROP CONSTRAINT categories_kind_check;
ALTER TABLE categories ADD CONSTRAINT categories_kind_check
    CHECK (kind IN ('consumption', 'investment', 'income'));

ALTER TABLE income
    ADD COLUMN category_id uuid,
    ADD FOREIGN KEY (household_id, category_id) REFERENCES categories (household_id, id) ON DELETE RESTRICT;

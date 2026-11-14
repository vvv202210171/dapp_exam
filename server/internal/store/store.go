// SQLite 账本。账户、已签名交易和卡流水都在这里。卡号密文单独查询，列表接口不带回完整卡号。
package store

import (
	"context"
	"database/sql"
	"errors"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"aegis/internal/domain"
	"aegis/internal/platform"

	_ "modernc.org/sqlite"
)

type Store struct {
	db *sql.DB
}

// 打开 SQLite，建表并开启 WAL。
func Open(path string) (*Store, error) {
	if err := mkdir(path); err != nil {
		return nil, err
	}
	// Windows 路径里的反斜杠会破坏 DSN，先转成斜杠。单连接避免 SQLite 写锁互相等待。
	dsn := "file:" + filepath.ToSlash(path) + "?_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(`PRAGMA journal_mode = WAL;`); err != nil {
		_ = db.Close()
		return nil, err
	}
	if _, err := db.Exec(schema); err != nil {
		_ = db.Close()
		return nil, err
	}
	return &Store{db: db}, nil
}

// 确保数据库文件的目录存在。
func mkdir(path string) error {
	return os.MkdirAll(filepath.Dir(path), 0o755)
}

// 关闭数据库连接。
func (s *Store) Close() error { return s.db.Close() }

// 按五条链地址创建或更新账户。
func (s *Store) SyncAccount(ctx context.Context, label string, addrs []domain.Address) (domain.Account, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return domain.Account{}, err
	}
	defer tx.Rollback()

	found := map[string]struct{}{}
	for _, a := range addrs {
		var id string
		err := tx.QueryRowContext(ctx, `SELECT account_id FROM addresses WHERE chain = ? AND address = ?`, string(a.Chain), a.Address).Scan(&id)
		if errors.Is(err, sql.ErrNoRows) {
			continue
		}
		if err != nil {
			return domain.Account{}, err
		}
		found[id] = struct{}{}
	}
	if len(found) > 1 {
		return domain.Account{}, domain.E("CONFLICT", "这些地址属于不同账户", "一套助记词对应一个账户。请不要把两套钱包的地址混在一起同步。")
	}
	var accountID string
	now := time.Now().UnixMilli()
	if len(found) == 1 {
		for id := range found {
			accountID = id
		}
		if _, err := tx.ExecContext(ctx, `UPDATE accounts SET label = ? WHERE id = ?`, label, accountID); err != nil {
			return domain.Account{}, err
		}
	} else {
		accountID = platform.NewID()
		if _, err := tx.ExecContext(ctx, `INSERT INTO accounts(id, label, created_at) VALUES(?,?,?)`, accountID, label, now); err != nil {
			return domain.Account{}, err
		}
	}
	for _, a := range addrs {
		_, err := tx.ExecContext(ctx, `
			INSERT INTO addresses(account_id, chain, address, derivation_path)
			VALUES(?,?,?,?)
			ON CONFLICT(account_id, chain) DO UPDATE SET
				address = excluded.address,
				derivation_path = excluded.derivation_path
		`, accountID, string(a.Chain), a.Address, a.Path)
		if err != nil {
			if strings.Contains(strings.ToLower(err.Error()), "unique") {
				return domain.Account{}, domain.E("CONFLICT", "地址已经登记在别的账户上", "换一套助记词会得到另一组地址。")
			}
			return domain.Account{}, err
		}
	}
	if err := tx.Commit(); err != nil {
		return domain.Account{}, err
	}
	return s.GetAccount(ctx, accountID)
}

// 按 ID 读出账户和它的地址。
func (s *Store) GetAccount(ctx context.Context, id string) (domain.Account, error) {
	var label string
	var created int64
	err := s.db.QueryRowContext(ctx, `SELECT label, created_at FROM accounts WHERE id = ?`, id).Scan(&label, &created)
	if errors.Is(err, sql.ErrNoRows) {
		return domain.Account{}, domain.E("NOT_FOUND", "账户不存在", "重新解锁钱包，让浏览器再同步一次地址。")
	}
	if err != nil {
		return domain.Account{}, err
	}
	rows, err := s.db.QueryContext(ctx, `SELECT chain, address, derivation_path FROM addresses WHERE account_id = ?`, id)
	if err != nil {
		return domain.Account{}, err
	}
	defer rows.Close()
	var addrs []domain.Address
	for rows.Next() {
		var a domain.Address
		if err := rows.Scan(&a.Chain, &a.Address, &a.Path); err != nil {
			return domain.Account{}, err
		}
		addrs = append(addrs, a)
	}
	if err := rows.Err(); err != nil {
		return domain.Account{}, err
	}
	sort.Slice(addrs, func(i, j int) bool { return chainRank(addrs[i].Chain) < chainRank(addrs[j].Chain) })
	return domain.Account{ID: id, Label: label, CreatedAt: time.UnixMilli(created).UTC(), Addresses: addrs}, nil
}

// 插入一笔本地发起的转账记录。
func (s *Store) InsertTransfer(ctx context.Context, t domain.Transfer) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO transfers(id, account_id, chain, asset, from_addr, to_addr, amount, fee, tx_hash, signed_payload, status, detail, created_at, updated_at)
		VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
	`, t.ID, t.AccountID, string(t.Chain), t.Asset, t.From, t.To, t.Amount, t.Fee, t.TxHash, t.SignedPayload, t.Status, t.Detail, t.CreatedAt.UnixMilli(), t.UpdatedAt.UnixMilli())
	return err
}

// 更新交易哈希、状态和说明。
func (s *Store) UpdateTransfer(ctx context.Context, t domain.Transfer) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE transfers SET tx_hash = ?, status = ?, detail = ?, updated_at = ? WHERE id = ?
	`, t.TxHash, t.Status, t.Detail, t.UpdatedAt.UnixMilli(), t.ID)
	return err
}

// 按 ID 读一笔转账。
func (s *Store) GetTransfer(ctx context.Context, id string) (domain.Transfer, error) {
	row := s.db.QueryRowContext(ctx, transferSelect+` WHERE id = ?`, id)
	t, err := scanTransfer(row)
	if errors.Is(err, sql.ErrNoRows) {
		return domain.Transfer{}, domain.E("NOT_FOUND", "交易不存在", "")
	}
	return t, err
}

// 列出某账户发出的转账，新的在前。
func (s *Store) ListTransfers(ctx context.Context, accountID string) ([]domain.Transfer, error) {
	rows, err := s.db.QueryContext(ctx, transferSelect+` WHERE account_id = ? ORDER BY created_at DESC LIMIT 100`, accountID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.Transfer
	for rows.Next() {
		t, err := scanTransfer(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	if out == nil {
		out = []domain.Transfer{}
	}
	return out, rows.Err()
}

type CardInput struct {
	AccountID string
	Kind      string
	Status    string
	Last4     string
	PANEnc    string
	CVVEnc    string
	Expiry    string
	Holder    string
}

// 写入一张卡。卡号和 CVV 以密文保存。
func (s *Store) CreateCard(ctx context.Context, in CardInput) (domain.Card, error) {
	if _, err := s.GetAccount(ctx, in.AccountID); err != nil {
		return domain.Card{}, err
	}
	card := domain.Card{
		ID:        platform.NewID(),
		AccountID: in.AccountID,
		Kind:      in.Kind,
		Status:    in.Status,
		Last4:     in.Last4,
		Expiry:    in.Expiry,
		Holder:    in.Holder,
		Currency:  "USD",
		Available: 0,
		CreatedAt: time.Now().UTC(),
	}
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO cards(id, account_id, kind, status, last4, pan_enc, cvv_enc, expiry, holder, currency, available, created_at)
		VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
	`, card.ID, card.AccountID, card.Kind, card.Status, card.Last4, in.PANEnc, in.CVVEnc, card.Expiry, card.Holder, card.Currency, 0, card.CreatedAt.UnixMilli())
	if err != nil {
		return domain.Card{}, err
	}
	return card, nil
}

// 读取卡的展示信息，不含完整卡号。
func (s *Store) GetCard(ctx context.Context, id string) (domain.Card, error) {
	card, _, _, err := s.card(ctx, id)
	return card, err
}

// 单独读出卡号和 CVV 的密文。
func (s *Store) Secrets(ctx context.Context, id string) (string, string, error) {
	_, pan, cvv, err := s.card(ctx, id)
	return pan, cvv, err
}

// 列出某账户的卡。
func (s *Store) ListCards(ctx context.Context, accountID string) ([]domain.Card, error) {
	rows, err := s.db.QueryContext(ctx, cardSelect+` WHERE account_id = ? ORDER BY created_at DESC`, accountID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.Card
	for rows.Next() {
		card, err := scanCard(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, card)
	}
	if out == nil {
		out = []domain.Card{}
	}
	return out, rows.Err()
}

// 列出一张卡的全部流水。
func (s *Store) ListEntries(ctx context.Context, cardID string) ([]domain.LedgerEntry, error) {
	if _, err := s.GetCard(ctx, cardID); err != nil {
		return nil, err
	}
	rows, err := s.db.QueryContext(ctx, entrySelect+` WHERE card_id = ? ORDER BY created_at ASC, rowid ASC`, cardID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.LedgerEntry
	for rows.Next() {
		e, err := scanEntry(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	if out == nil {
		out = []domain.LedgerEntry{}
	}
	return out, rows.Err()
}

type Draft struct {
	Kind     string
	Amount   int64
	Merchant string
	MCC      string
	Note     string
	Ref      string
}

// Move 在同一事务里改余额。相同幂等键直接返回旧流水，并且先于状态校验，这样超时重试不会被“已注销”挡住。
func (s *Store) Move(ctx context.Context, cardID string, drafts []Draft, policy func(kind, status string) error) (domain.MoveResult, error) {
	if len(drafts) == 0 || drafts[0].Ref == "" {
		return domain.MoveResult{}, domain.E("INVALID_ARGUMENT", "缺少幂等键", "每次充值、消费、提现都带一个 Idempotency-Key，超时重试才不会记两笔。")
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return domain.MoveResult{}, err
	}
	defer tx.Rollback()

	card, err := getCardTx(ctx, tx, cardID)
	if err != nil {
		return domain.MoveResult{}, err
	}
	existing, err := entriesByRefs(ctx, tx, cardID, drafts[0].Ref, drafts[0].Ref+":fee")
	if err != nil {
		return domain.MoveResult{}, err
	}
	if len(existing) > 0 {
		if err := tx.Commit(); err != nil {
			return domain.MoveResult{}, err
		}
		return domain.MoveResult{Card: card, Entries: existing, Replayed: true}, nil
	}
	if err := policy(card.Kind, card.Status); err != nil {
		return domain.MoveResult{}, err
	}
	balance := int64(card.Available)
	now := time.Now().UnixMilli()
	written := make([]domain.LedgerEntry, 0, len(drafts))
	for i, d := range drafts {
		if d.Amount <= 0 {
			return domain.MoveResult{}, domain.E("INVALID_ARGUMENT", "金额必须大于 0", "")
		}
		next, err := nextBalance(balance, d)
		if err != nil {
			return domain.MoveResult{}, err
		}
		balance = next
		entry := domain.LedgerEntry{
			ID:           platform.NewID(),
			CardID:       cardID,
			Kind:         d.Kind,
			Amount:       domain.Cents(d.Amount),
			BalanceAfter: domain.Cents(balance),
			Merchant:     d.Merchant,
			MCC:          d.MCC,
			Ref:          d.Ref,
			Note:         d.Note,
			CreatedAt:    time.UnixMilli(now + int64(i)).UTC(),
		}
		_, err = tx.ExecContext(ctx, `
			INSERT INTO ledger_entries(id, card_id, kind, amount, balance_after, merchant, mcc, ref, note, created_at)
			VALUES(?,?,?,?,?,?,?,?,?,?)
		`, entry.ID, entry.CardID, entry.Kind, d.Amount, balance, entry.Merchant, entry.MCC, entry.Ref, entry.Note, entry.CreatedAt.UnixMilli())
		if err != nil {
			return domain.MoveResult{}, err
		}
		written = append(written, entry)
	}
	if _, err := tx.ExecContext(ctx, `UPDATE cards SET available = ? WHERE id = ?`, balance, cardID); err != nil {
		return domain.MoveResult{}, err
	}
	if err := tx.Commit(); err != nil {
		return domain.MoveResult{}, err
	}
	card.Available = domain.Cents(balance)
	return domain.MoveResult{Card: card, Entries: written}, nil
}

// 按状态机推进卡的状态。
func (s *Store) Transition(ctx context.Context, cardID, action string) (domain.Card, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return domain.Card{}, err
	}
	defer tx.Rollback()
	card, err := getCardTx(ctx, tx, cardID)
	if err != nil {
		return domain.Card{}, err
	}
	next, ok := transitions[card.Kind][card.Status][action]
	if !ok {
		return domain.Card{}, domain.E("INVALID_ARGUMENT", "当前状态不能做这个操作", "实体卡要按 申请 → 制卡 → 寄出 → 激活 走。冻结的卡要先解冻。")
	}
	if _, err := tx.ExecContext(ctx, `UPDATE cards SET status = ? WHERE id = ?`, next, cardID); err != nil {
		return domain.Card{}, err
	}
	if err := tx.Commit(); err != nil {
		return domain.Card{}, err
	}
	card.Status = next
	return card, nil
}

// 按充值、消费、提现或手续费计算下一次余额。
func nextBalance(balance int64, d Draft) (int64, error) {
	switch d.Kind {
	case "topup":
		if balance > math.MaxInt64-d.Amount {
			return 0, domain.E("INVALID_ARGUMENT", "余额超出上限", "")
		}
		return balance + d.Amount, nil
	case "spend":
		if balance < d.Amount {
			return 0, domain.E("INSUFFICIENT_FUNDS", "卡余额不足", "消费只扣这一笔金额。")
		}
		return balance - d.Amount, nil
	case "withdraw", "fee":
		if balance < d.Amount {
			return 0, domain.E("INSUFFICIENT_FUNDS", "卡余额不足", "提现是外扣手续费，可用余额要盖住金额加手续费。")
		}
		return balance - d.Amount, nil
	default:
		return 0, domain.E("INVALID_ARGUMENT", "未知的账务类型", "")
	}
}

// 读卡，并额外取出卡号和 CVV 密文。
func (s *Store) card(ctx context.Context, id string) (domain.Card, string, string, error) {
	card, err := scanCard(s.db.QueryRowContext(ctx, cardSelect+` WHERE id = ?`, id))
	if errors.Is(err, sql.ErrNoRows) {
		return domain.Card{}, "", "", domain.E("NOT_FOUND", "卡不存在", "")
	}
	if err != nil {
		return domain.Card{}, "", "", err
	}
	var pan, cvv string
	err = s.db.QueryRowContext(ctx, `SELECT pan_enc, cvv_enc FROM cards WHERE id = ?`, id).Scan(&pan, &cvv)
	return card, pan, cvv, err
}

// 在事务里锁住并读出一张卡。
func getCardTx(ctx context.Context, tx *sql.Tx, id string) (domain.Card, error) {
	card, err := scanCard(tx.QueryRowContext(ctx, cardSelect+` WHERE id = ?`, id))
	if errors.Is(err, sql.ErrNoRows) {
		return domain.Card{}, domain.E("NOT_FOUND", "卡不存在", "")
	}
	return card, err
}

// 按幂等键查找已经记过的流水，用来防重复提交。
func entriesByRefs(ctx context.Context, tx *sql.Tx, cardID, ref, feeRef string) ([]domain.LedgerEntry, error) {
	rows, err := tx.QueryContext(ctx, entrySelect+` WHERE card_id = ? AND ref IN (?, ?) ORDER BY created_at ASC`, cardID, ref, feeRef)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.LedgerEntry
	for rows.Next() {
		e, err := scanEntry(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

type scanner interface {
	Scan(dest ...any) error
}

// 把一行查询结果扫成转账记录。
func scanTransfer(row scanner) (domain.Transfer, error) {
	var t domain.Transfer
	var created, updated int64
	err := row.Scan(&t.ID, &t.AccountID, &t.Chain, &t.Asset, &t.From, &t.To, &t.Amount, &t.Fee, &t.TxHash, &t.SignedPayload, &t.Status, &t.Detail, &created, &updated)
	if err != nil {
		return domain.Transfer{}, err
	}
	t.CreatedAt = time.UnixMilli(created).UTC()
	t.UpdatedAt = time.UnixMilli(updated).UTC()
	return t, nil
}

// 把一行查询结果扫成卡。
func scanCard(row scanner) (domain.Card, error) {
	var c domain.Card
	var available, created int64
	err := row.Scan(&c.ID, &c.AccountID, &c.Kind, &c.Status, &c.Last4, &c.Expiry, &c.Holder, &c.Currency, &available, &created)
	if err != nil {
		return domain.Card{}, err
	}
	c.Available = domain.Cents(available)
	c.CreatedAt = time.UnixMilli(created).UTC()
	return c, nil
}

// 把一行查询结果扫成流水。
func scanEntry(row scanner) (domain.LedgerEntry, error) {
	var e domain.LedgerEntry
	var amount, after, created int64
	err := row.Scan(&e.ID, &e.CardID, &e.Kind, &amount, &after, &e.Merchant, &e.MCC, &e.Ref, &e.Note, &created)
	if err != nil {
		return domain.LedgerEntry{}, err
	}
	e.Amount = domain.Cents(amount)
	e.BalanceAfter = domain.Cents(after)
	e.CreatedAt = time.UnixMilli(created).UTC()
	return e, nil
}

// 给五条链排一个固定顺序，方便列表展示。
func chainRank(id domain.ChainID) int {
	switch id {
	case domain.Bitcoin:
		return 0
	case domain.Ethereum:
		return 1
	case domain.BNB:
		return 2
	case domain.Solana:
		return 3
	case domain.Tron:
		return 4
	default:
		return 9
	}
}

const transferSelect = `SELECT id, account_id, chain, asset, from_addr, to_addr, amount, fee, tx_hash, signed_payload, status, detail, created_at, updated_at FROM transfers`

const cardSelect = `SELECT id, account_id, kind, status, last4, expiry, holder, currency, available, created_at FROM cards`

const entrySelect = `SELECT id, card_id, kind, amount, balance_after, merchant, mcc, ref, note, created_at FROM ledger_entries`

var transitions = map[string]map[string]map[string]string{
	"virtual": {
		"active": {"freeze": "frozen", "close": "closed"},
		"frozen": {"unfreeze": "active", "close": "closed"},
	},
	"physical": {
		"requested": {"issue": "issued", "close": "closed"},
		"issued":    {"ship": "shipped", "close": "closed"},
		"shipped":   {"activate": "active", "close": "closed"},
		"active":    {"freeze": "frozen", "close": "closed"},
		"frozen":    {"unfreeze": "active", "close": "closed"},
	},
}

const schema = `
CREATE TABLE IF NOT EXISTS accounts (
	id TEXT PRIMARY KEY,
	label TEXT NOT NULL,
	created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS addresses (
	account_id TEXT NOT NULL,
	chain TEXT NOT NULL,
	address TEXT NOT NULL,
	derivation_path TEXT NOT NULL,
	PRIMARY KEY (account_id, chain),
	UNIQUE (chain, address)
);
CREATE TABLE IF NOT EXISTS transfers (
	id TEXT PRIMARY KEY,
	account_id TEXT NOT NULL,
	chain TEXT NOT NULL,
	asset TEXT NOT NULL,
	from_addr TEXT NOT NULL,
	to_addr TEXT NOT NULL,
	amount TEXT NOT NULL,
	fee TEXT NOT NULL DEFAULT '',
	tx_hash TEXT NOT NULL DEFAULT '',
	signed_payload TEXT NOT NULL DEFAULT '',
	status TEXT NOT NULL,
	detail TEXT NOT NULL DEFAULT '',
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_transfers_account ON transfers(account_id, created_at DESC);
CREATE TABLE IF NOT EXISTS cards (
	id TEXT PRIMARY KEY,
	account_id TEXT NOT NULL,
	kind TEXT NOT NULL,
	status TEXT NOT NULL,
	last4 TEXT NOT NULL,
	pan_enc TEXT NOT NULL,
	cvv_enc TEXT NOT NULL,
	expiry TEXT NOT NULL,
	holder TEXT NOT NULL,
	currency TEXT NOT NULL,
	available INTEGER NOT NULL,
	created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_cards_account ON cards(account_id, created_at DESC);
CREATE TABLE IF NOT EXISTS ledger_entries (
	id TEXT PRIMARY KEY,
	card_id TEXT NOT NULL,
	kind TEXT NOT NULL,
	amount INTEGER NOT NULL,
	balance_after INTEGER NOT NULL,
	merchant TEXT NOT NULL DEFAULT '',
	mcc TEXT NOT NULL DEFAULT '',
	ref TEXT NOT NULL,
	note TEXT NOT NULL DEFAULT '',
	created_at INTEGER NOT NULL,
	UNIQUE (card_id, ref)
);
CREATE INDEX IF NOT EXISTS idx_ledger_card ON ledger_entries(card_id, created_at);
`

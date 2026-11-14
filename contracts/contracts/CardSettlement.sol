// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @title 卡结算合约
/// @notice 用户存入受支持的稳定币，运营方按账单号结算消费，用户提走还没结算的部分。
///         这是卡账本的链上一半；Go 服务里的美元账本是沙箱，不会假装已经上链。
contract CardSettlement {
    error ZeroAmount();
    error ZeroAddress();
    error UnsupportedToken(address token);
    error NotOwner();
    error NotOperator();
    error InsufficientAvailable(uint256 available, uint256 needed);
    error DuplicateRef(bytes32 ref);
    error Reentrancy();
    error TransferFailed();

    event Deposited(address indexed user, address indexed token, uint256 amount);
    event Settled(address indexed user, address indexed token, uint256 amount, bytes32 indexed ref);
    event Withdrawn(address indexed user, address indexed token, uint256 amount);
    event OperatorUpdated(address indexed operator, bool allowed);
    event TokenUpdated(address indexed token, bool allowed);

    // 用 1/2 而不是 bool，避免未初始化的存储槽被当成已上锁。
    uint256 private constant UNLOCKED = 1;
    uint256 private constant LOCKED = 2;

    address public owner;
    uint256 private lockStatus = UNLOCKED;
    mapping(address => bool) public operators;
    mapping(address => bool) public supportedToken;
    mapping(address => mapping(address => uint256)) private _available;
    mapping(bytes32 => bool) public settledRefs;

    // 进入函数前上锁，返回前解开。重入会直接失败。
    modifier nonReentrant() {
        if (lockStatus == LOCKED) revert Reentrancy();
        lockStatus = LOCKED;
        _;
        lockStatus = UNLOCKED;
    }

    // 只允许合约所有者调用。
    modifier onlyOwner() {
        if (msg.sender != owner) revert NotOwner();
        _;
    }

    // 设置所有者、初始运营方，以及允许存入的代币。
    constructor(address initialOwner, address[] memory tokens) {
        if (initialOwner == address(0)) revert ZeroAddress();
        owner = initialOwner;
        operators[initialOwner] = true;
        emit OperatorUpdated(initialOwner, true);
        for (uint256 i = 0; i < tokens.length; i++) {
            if (tokens[i] == address(0)) revert ZeroAddress();
            supportedToken[tokens[i]] = true;
            emit TokenUpdated(tokens[i], true);
        }
    }

    // 查询某用户在某种代币上还没被结算的余额。
    function available(address user, address token) external view returns (uint256) {
        return _available[user][token];
    }

    // 增加或取消运营方。只有所有者可以调用。
    function setOperator(address operator, bool allowed) external onlyOwner {
        if (operator == address(0)) revert ZeroAddress();
        operators[operator] = allowed;
        emit OperatorUpdated(operator, allowed);
    }

    // 允许或禁止某种代币进入结算合约。
    function setToken(address token, bool allowed) external onlyOwner {
        if (token == address(0)) revert ZeroAddress();
        supportedToken[token] = allowed;
        emit TokenUpdated(token, allowed);
    }

    /// @notice 先把代币拉进来，再记 available。用户必须事先 approve。
    function deposit(address token, uint256 amount) external nonReentrant {
        if (amount == 0) revert ZeroAmount();
        if (!supportedToken[token]) revert UnsupportedToken(token);
        _pull(token, msg.sender, amount);
        _available[msg.sender][token] += amount;
        emit Deposited(msg.sender, token, amount);
    }

    /// @notice 先减账再打币，同一个 bytes32 账单号不能结算两次。
    function settle(address user, address token, uint256 amount, bytes32 ref) external nonReentrant {
        if (!operators[msg.sender]) revert NotOperator();
        if (amount == 0) revert ZeroAmount();
        if (user == address(0)) revert ZeroAddress();
        if (settledRefs[ref]) revert DuplicateRef(ref);
        uint256 bal = _available[user][token];
        if (bal < amount) revert InsufficientAvailable(bal, amount);
        settledRefs[ref] = true;
        _available[user][token] = bal - amount;
        _push(token, msg.sender, amount);
        emit Settled(user, token, amount, ref);
    }

    /// @notice 提走尚未结算的余额。减账发生在转币之前，配合重入锁。
    function withdraw(address token, uint256 amount) external nonReentrant {
        if (amount == 0) revert ZeroAmount();
        uint256 bal = _available[msg.sender][token];
        if (bal < amount) revert InsufficientAvailable(bal, amount);
        _available[msg.sender][token] = bal - amount;
        _push(token, msg.sender, amount);
        emit Withdrawn(msg.sender, token, amount);
    }

    // transferFrom(address,address,uint256) = 0x23b872dd。返回空或 true 都算成功，兼容 USDT。
    function _pull(address token, address from, uint256 amount) private {
        (bool ok, bytes memory data) = token.call(abi.encodeWithSelector(0x23b872dd, from, address(this), amount));
        if (!ok || (data.length != 0 && !abi.decode(data, (bool)))) revert TransferFailed();
    }

    // transfer(address,uint256) = 0xa9059cbb。部分代币成功时不返回 bool。
    function _push(address token, address to, uint256 amount) private {
        (bool ok, bytes memory data) = token.call(abi.encodeWithSelector(0xa9059cbb, to, amount));
        if (!ok || (data.length != 0 && !abi.decode(data, (bool)))) revert TransferFailed();
    }
}

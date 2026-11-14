// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

interface ICardSettlement {
    // 结算合约的存入方法，测试用来把代币打进去。
    function deposit(address token, uint256 amount) external;
    // 结算合约的提现方法，transfer 里会回头调用它。
    function withdraw(address token, uint256 amount) external;
}

/// @notice 恶意代币。transfer 打给攻击者时回头调用 settlement.withdraw，用来证明重入锁生效。
contract MaliciousToken {
    mapping(address => uint256) public balanceOf;
    mapping(address => mapping(address => uint256)) public allowance;

    ICardSettlement public settlement;
    address public attacker;
    bool public armed;
    bool internal entered;

    // 指定要攻击的结算合约和收款地址。
    function configure(address settlement_, address attacker_) external {
        settlement = ICardSettlement(settlement_);
        attacker = attacker_;
    }

    // 打开或关闭重入。
    function setArmed(bool value) external {
        armed = value;
    }

    // 给测试地址增发恶意代币。
    function mint(address to, uint256 amount) external {
        balanceOf[to] += amount;
    }

    // 先给自己铸币并授权，再存进结算合约。
    function fundSettlement(uint256 amount) external {
        balanceOf[address(this)] += amount;
        allowance[address(this)][address(settlement)] = amount;
        settlement.deposit(address(this), amount);
    }

    // 授权别人划走余额。
    function approve(address spender, uint256 amount) external returns (bool) {
        allowance[msg.sender][spender] = amount;
        return true;
    }

    // 普通转账。武装后向攻击者转账时会重入 withdraw。
    function transfer(address to, uint256 amount) external returns (bool) {
        balanceOf[msg.sender] -= amount;
        balanceOf[to] += amount;
        if (armed && !entered && to == attacker) {
            entered = true; // 只重入一次，避免测试自己把 gas 打光
            settlement.withdraw(address(this), 1);
        }
        return true;
    }

    // 按授权划转。这里故意不触发重入，方便对照。
    function transferFrom(address from, address to, uint256 amount) external returns (bool) {
        uint256 allowed = allowance[from][msg.sender];
        if (allowed != type(uint256).max) {
            require(allowed >= amount, "allowance");
            allowance[from][msg.sender] = allowed - amount;
        }
        balanceOf[from] -= amount;
        balanceOf[to] += amount;
        return true;
    }
}

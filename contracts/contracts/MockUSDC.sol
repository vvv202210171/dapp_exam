// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

// 测试用的 6 位小数稳定币。无限授权不再扣 allowance，和主流 USDC 一样。
contract MockUSDC {
    string public name = "Mock USDC";
    string public symbol = "USDC";
    uint8 public decimals = 6;

    mapping(address => uint256) public balanceOf;
    mapping(address => mapping(address => uint256)) public allowance;

    // 给测试地址增发代币。
    function mint(address to, uint256 amount) external {
        balanceOf[to] += amount;
    }

    // 授权结算合约划走代币。
    function approve(address spender, uint256 amount) external returns (bool) {
        allowance[msg.sender][spender] = amount;
        return true;
    }

    // 转出调用者自己的余额。
    function transfer(address to, uint256 amount) external returns (bool) {
        balanceOf[msg.sender] -= amount;
        balanceOf[to] += amount;
        return true;
    }

    // 按授权从别人的余额划转。无限授权不减少额度。
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

package aegis.config;

/**
 * 卡号加密密钥。生产环境应换成 KMS，而不是把 32 字节放在本地文件。
 *
 * @param bytes 32 字节 AES 密钥
 */
public record CardKey(byte[] bytes) {}

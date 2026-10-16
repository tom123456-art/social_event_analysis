package com.social.hotspot.integration.spark;

/** 保存远程命令的标准输出、错误输出和退出码。 */
public record RemoteCommandResult(String stdout, String stderr, int exitCode) {}

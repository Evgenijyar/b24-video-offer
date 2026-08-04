package ru.abs7.videooffer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import ru.abs7.videooffer.bitrix.BitrixProperties;
import ru.abs7.videooffer.kontur.KonturTalkProperties;

@Configuration
@EnableConfigurationProperties({KonturTalkProperties.class, BitrixProperties.class})
public class AppConfiguration {}

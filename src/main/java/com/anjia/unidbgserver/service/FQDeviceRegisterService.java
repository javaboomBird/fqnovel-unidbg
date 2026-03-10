package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.config.FQApiProperties;
import com.anjia.unidbgserver.dto.DeviceInfo;
import com.anjia.unidbgserver.dto.DeviceRegisterRequest;
import com.anjia.unidbgserver.dto.DeviceRegisterResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 设备自动重注册服务
 * 在检测到风控（ILLEGAL_ACCESS）时，自动生成新设备并热更新内存配置，无需重启
 */
@Slf4j
@Service
public class FQDeviceRegisterService {

    @Resource
    private DeviceManagementService deviceManagementService;

    @Resource
    private FQApiProperties fqApiProperties;

    @Resource
    private FQRegisterKeyService registerKeyService;

    /**
     * 自动重新注册设备，热更新内存配置，立即生效
     *
     * @return 是否成功
     */
    public boolean reRegister() {
        try {
            log.info("开始自动重新注册设备...");

            // 生成新设备（使用真实品牌，纯Java实现，不依赖外部Python脚本）
            DeviceRegisterRequest request = DeviceRegisterRequest.builder()
                .useRealBrand(true)
                .useRealAlgorithm(true)
                .autoUpdateConfig(false)
                .autoRestart(false)
                .build();

            DeviceRegisterResponse resp = deviceManagementService.registerDevice(request).get();
            if (!resp.getSuccess() || resp.getDeviceInfo() == null) {
                log.error("新设备生成失败: {}", resp.getMessage());
                return false;
            }

            DeviceInfo deviceInfo = resp.getDeviceInfo();
            log.info("新设备生成成功 - 品牌: {}, 型号: {}, 设备ID: {}",
                deviceInfo.getDeviceBrand(), deviceInfo.getDeviceType(), deviceInfo.getDeviceId());

            // 直接更新内存中的 FQApiProperties，无需重启即可生效
            FQApiProperties.Device device = fqApiProperties.getDevice();
            device.setDeviceId(deviceInfo.getDeviceId());
            device.setInstallId(deviceInfo.getInstallId());
            device.setCdid(deviceInfo.getCdid());
            device.setDeviceBrand(deviceInfo.getDeviceBrand());
            device.setDeviceType(deviceInfo.getDeviceType());
            device.setResolution(deviceInfo.getResolution());
            device.setDpi(deviceInfo.getDpi());
            device.setHostAbi(deviceInfo.getHostAbi());
            device.setRomVersion(deviceInfo.getRomVersion());
            if (deviceInfo.getUserAgent() != null) {
                fqApiProperties.setUserAgent(deviceInfo.getUserAgent());
            }
            if (deviceInfo.getCookie() != null) {
                fqApiProperties.setCookie(deviceInfo.getCookie());
            }

            // 清除 RegisterKey 缓存并重置 FqVariable，使后续请求使用新设备信息
            registerKeyService.clearCache();
            registerKeyService.resetFqVariable();

            // 异步持久化到 application.yml（失败不影响当前请求）
            deviceManagementService.updateDeviceConfig(deviceInfo)
                .whenComplete((success, ex) -> {
                    if (ex != null) {
                        log.warn("异步持久化设备配置失败", ex);
                    } else if (Boolean.TRUE.equals(success)) {
                        log.info("新设备配置已持久化到配置文件");
                    } else {
                        log.warn("持久化设备配置返回失败，但内存已更新，当次运行有效");
                    }
                });

            log.info("设备重注册完成，新设备立即生效 - 品牌: {}, 型号: {}",
                deviceInfo.getDeviceBrand(), deviceInfo.getDeviceType());
            return true;

        } catch (Exception e) {
            log.error("自动重新注册设备失败", e);
            return false;
        }
    }
}

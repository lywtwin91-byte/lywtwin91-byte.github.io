# Pixel 11 베타(CP41.260831.011) — VoLTE 토글 표시/활성 조건

디컴파일 대상: `SettingsGoogle.apk`(Enhanced4gBasePreferenceController, VolteQueryImsState), `ims-common.jar`(ImsManager),
`ImsStack.apk`(ServiceCaps, ImsStackMain, ImsCarrierResolver), `CarrierSettings.apk`(CarrierConfigService, UpdateService).
NSX를 직접 다루는 코드는 없습니다. 아래는 모두 **carrier config 값**으로 결정됩니다.

## 1. 설정값이 만들어지는 순서 (CarrierSettings `onLoadConfig`)

1. 펌웨어 `/product/etc/CarrierSettings/<carrier>.pb`
2. **서버 오버라이드**: Phenotype 플래그 `CarrierSettingsOverride__carrier_settings_overrides`에 통신사 이름(canonical name)별 `CarrierSettings`가 들어 있고, 펌웨어 값 위에 `putAll`로 덮어씁니다.
   적용 조건(`isOverrideApplicable`): `override.version % 1e9 != 0` 이고 `override.version / 1e9 >= pb.version / 1e9`.
   적용되면 `carrier_config_version_string` 뒤에 ` *`가 붙습니다(설정 > 휴대전화 정보 > 통신사 설정 버전에서 확인 가능).
3. 서버에서 다운로드한 통신사 목록·설정 `.pb`(`UpdateService.downloadAndApplyUpdatesIfAvailable`). 화면이 꺼져 있거나 시스템 OTA 시간대일 때만 적용됩니다.
4. 로컬 오버라이드(메모리 상의 디버그용 값). user 빌드에서는 사실상 쓰이지 않습니다.

→ NSX 데이터가 반영된다면 2·3번 경로로 **펌웨어 업데이트 없이** 바뀔 수 있습니다.

## 2. 토글이 보이는지 (`Enhanced4gBasePreferenceController.getAvailabilityStatus`)

| 순서 | 조건 | 결과 |
|---|---|---|
| 1 | 타이틀 모드 불일치(`enhanced_4g_lte_title_variant_int`, `show_4g_for_lte_data_icon_bool`로 "VoLTE / 4G 통화 / 고급 통화" 중 하나를 선택) | 해당 항목 숨김 |
| 2 | **VoIMS opt-in**(프로비저닝 키 68 = `voims_opt_in_status`가 1) | **무조건 표시·활성** (아래 조건 무시) |
| 3 | 식별되지 않은 통신사(`carrier_config_applied_bool` 없음) 또는 `hide_enhanced_4g_lte_bool=true` | 숨김 |
| 4 | `isReadyToVoLte()` 거짓 → 숨김. 참이 되려면 아래 3가지 모두 필요: | |
|   | · 플랫폼 허용: `config_device_volte_available`(기기) && `carrier_volte_available_bool` && GBA 유효(`carrier_ims_gba_required_bool`이면 ISIM IST의 GBA 비트 필요). 또는 VoIMS opt-in, 또는 디버그 속성 `persist.dbg.volte_avail_ovr=1` | |
|   | · 프로비저닝: `carrier_volte_provisioning_required_bool`(또는 `ims.mmtel_requires_provisioning_bundle`)이면 프로비저닝 완료 필요 | |
|   | · IMS 서비스 상태가 준비됨 | |
| 5 | 통화 중이 아님 && `editable_enhanced_4g_lte_bool=true` && (TTY 꺼짐 또는 TTY over VoLTE 지원) | 표시 + 조작 가능, 아니면 표시만(회색) |
| – | 비행기 모드 | 회색 |

## 3. 토글 값(ON/OFF) (`ImsManager.isEnhanced4gLteModeSettingEnabledByUser`)

- 사용자가 한 번도 바꾸지 않았거나(`volte_vt_enabled` = -1), 토글이 숨김/변경 불가이면 → **`enhanced_4g_lte_on_by_default_bool` 값** (AOSP 기본 true)
- VoIMS opt-in이면 기본값 강제를 건너뛰고 사용자 값을 사용
- 사용자가 바꾼 값은 SIM(subscription)별로 `volte_vt_enabled`에 저장됩니다.
- 끌 때 `volte_5g_limited_alert_dialog_bool`이면서 NR을 쓸 수 있으면 "5G 제한" 경고 대화상자를 띄웁니다.

**VoLTE가 실제로 켜지는 조건** (`updateVoiceCellFeatureValue`): 플랫폼 허용 && 토글 ON && (TTY 꺼짐 또는 TTY on VoLTE) && 프로비저닝 완료.
VoNR은 여기에 `isImsOverNrEnabledByPlatform` && VoNR 프로비저닝이 추가로 필요합니다.

ImsStack(`ServiceCaps.updateServiceCapabilities`)도 같은 기준으로 `config_device_volte_available && carrier_volte_available_bool`일 때만 VoLTE 서비스를 엽니다.

## 4. 이 펌웨어의 통신사 639개 분류

| 분류 | 수 | 예 |
|---|---|---|
| A. VoLTE 불가 → 토글 숨김 | 38 | **skt_kr, kt_kr, lguplus_kr**, ufone_pk, zong_pk, MVNO·IoT |
| B. VoLTE 항상 ON, 토글 숨김 | 240 (GBA 필요 47, 프로비저닝 필요 3 포함) | 1and1_de, airtel_in, orange_fr… |
| C. 토글 표시, 변경 불가(ON 고정) | 6 | kpn_nl, tdc_dk, videotron_ca… |
| D. 토글 표시, 기본 ON | 242 | a1_at, 2degrees_nz… |
| **E. 토글 표시, 기본 OFF (사용자가 켜야 함)** | **113** | 2026-03 일괄 추가된 템플릿형 프로필 102개 포함 |

NSX 등록 통신사에 대해 보도된 "기본 OFF, 사용자 활성화" 동작은 **E 유형**(`carrier_volte_available_bool=true`, `enhanced_4g_lte_on_by_default_bool=false`, 숨김·편집 불가 키 없음)에 해당합니다.
중국 3사·CSL은 `enhanced_4g_lte_on_by_default`를 따로 지정하지 않아 **D 유형(기본 ON)**입니다.

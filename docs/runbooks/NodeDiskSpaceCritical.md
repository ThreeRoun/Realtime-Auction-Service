---
alert: NodeDiskSpaceCritical
severity: critical
service: node
---

# NodeDiskSpaceCritical

## 의미와 영향

서버(k3s 노드) 파일시스템의 여유 공간이 **2분 이상 5% 미만**이다.

- 단일 서버라 **DB, Prometheus, Loki, 상품 이미지, 컨테이너 이미지가 모두 같은 디스크**를 쓴다.
  k3s의 PVC(local-path)는 요청 크기(5Gi 등)를 강제하지 않아서, 어느 하나가 커지면 전체 디스크가 찬다.
- 가득 차면 DB 쓰기가 실패해 **입찰과 상품 등록이 실패**하고, 심하면 Postgres가 멈춘다.
- 디스크 내용 확인과 정리는 서버 셸이 필요해 대부분 `[사람]` 항목이다.

## 확인

### C1. 어느 파일시스템이 얼마나 찼는가 `[도구: query_prometheus]`

```promql
1 - node_filesystem_avail_bytes{job="node-exporter", fstype!~"tmpfs|overlay|squashfs|nsfs|ramfs|fuse.*"}
  / node_filesystem_size_bytes{job="node-exporter", fstype!~"tmpfs|overlay|squashfs|nsfs|ramfs|fuse.*"}
```

알람 라벨의 `mountpoint`가 `/`이면 아래 확인을 모두 진행한다. 다른 마운트(`/boot` 등)면 서비스 영향은 작다.

### C2. 언제부터 빨리 늘었는가 `[도구: query_prometheus]`

```promql
deriv(node_filesystem_avail_bytes{job="node-exporter", mountpoint="/"}[1h])
```

음수 값이 클수록 빨리 차는 중이다. 급격히 줄기 시작한 시각을 C3~C4와 대조한다 (로그 폭증, 배포, 이미지 업로드 등).

### C3. 로그 폭증 여부 `[도구: query_loki]`

```logql
sum by (service) (bytes_over_time({namespace=~"auction|monitoring"}[1h]))
```

특정 서비스의 로그량이 평소보다 몇 배 많으면 Loki 저장 공간이 빨리 늘고 있는 것이다 (에러 반복, 디버그 로그 등).

### C4. 디스크를 차지하는 것 `[사람]`

```bash
df -h /
# k3s PVC 데이터 (DB, Prometheus, Loki, Grafana, 상품 이미지) - 디렉터리 이름에 네임스페이스와 PVC 이름이 들어 있다
sudo du -sh /var/lib/rancher/k3s/storage/* | sort -h
# 컨테이너 이미지 (배포마다 커밋 SHA 태그 이미지가 쌓인다)
sudo du -sh /var/lib/rancher/k3s/agent/containerd
sudo k3s crictl images | wc -l
# 시스템 로그
journalctl --disk-usage
# 그 밖에 큰 디렉터리 (같은 파일시스템만)
sudo du -xh --max-depth=2 / 2>/dev/null | sort -h | tail -15
```

| 많이 차지하는 곳 | 다음 |
| --- | --- |
| `containerd` (컨테이너 이미지) | 조치 A |
| journal 로그 | 조치 B |
| `*_monitoring_loki-data` | 조치 C |
| `*_monitoring_prometheus-data` | 보통 4GB 상한이라 원인이 아님. 넘었다면 조치 C와 같이 담당자 판단 |
| `*_auction_db-data-auction-db-0` | 조치 D |
| `*_auction_data-auction-s3-0` (상품 이미지 저장소) | 조치 D |
| GitHub runner 작업 폴더(`_work`), Docker, 개인 파일 | 조치 E |

## 조치

빠르고 안전한 순서(A → B → C)로 진행하며, 단계마다 `df -h /`로 여유를 확인한다. 10% 이상 확보되면 멈추고 원인 분석으로 넘어간다.

### A. 사용하지 않는 컨테이너 이미지 정리

- **조건**: C4에서 containerd 디렉터리가 크거나 이미지가 많음
- **실행**:

  ```bash
  sudo k3s crictl rmi --prune
  ```

- **주의**: 실행 중인 컨테이너가 쓰는 이미지는 지워지지 않는다. 다만 롤백용 이전 이미지도 지워져서, 롤백할 때 GHCR에서 다시 받는다(정상 동작).

### B. 시스템 로그(journal) 줄이기

- **조건**: C4에서 journal이 수 GB
- **실행**:

  ```bash
  sudo journalctl --vacuum-size=500M
  ```

### C. Loki 로그 저장량 줄이기 → 담당자 판단

- **조건**: C4에서 Loki 데이터가 큼, C3에서 특정 서비스 로그 폭증
- **실행**:
  1. 로그 폭증의 원인(에러 반복 등)을 먼저 해결한다. 폭증이 계속되면 다른 정리는 시간만 번다.
  2. 보존 기간(`k8s/monitoring/config/loki.yaml`의 `retention_period: 168h`)을 줄이는 PR을 올린다. compactor가 주기적으로 지우므로 즉시 줄지는 않는다.
  3. 당장 공간이 필요하면 Loki 데이터를 비우는 것도 방법이지만 **과거 로그를 모두 잃는다**. 담당자와 상의한 뒤에만 한다.

### D. DB 또는 상품 이미지가 큼 → 담당자 호출

- **조건**: C4에서 DB 데이터나 상품 이미지 디렉터리가 대부분을 차지
- **실행**: 담당자를 호출한다. 이 데이터는 서비스 데이터라 지우지 않는다. 디스크 증설이나 다른 데이터 정리로 공간을 만든다.
- **주의**: **DB 데이터 디렉터리 안의 파일(`pg_wal` 포함)은 절대 직접 지우지 않는다.** DB가 복구 불가능해진다.

### E. 서비스와 무관한 파일 → 소유자 확인 후 정리

- **조건**: C4에서 runner 작업 폴더, Docker 이미지/볼륨, 개인 파일이 큼
- **실행**: 서버는 팀이 함께 쓰므로 누구의 파일인지 확인한 뒤 정리한다.
  Docker를 쓰고 있다면 `docker system df`로 확인하고, 다른 프로젝트의 볼륨을 지우는 `docker volume prune` 같은 일괄 삭제는 하지 않는다.

## 해소 확인

```bash
df -h /     # 사용률 90% 미만 목표
```

여유가 5%를 넘으면 2분 뒤 RESOLVED가 온다. 15% 미만이면 `NodeDiskSpaceLow`(warning)가 계속 떠 있으니, 원인 분석과 정리를 이어서 한다.
DB가 쓰기 실패로 멈췄다면 [KubeStatefulSetReplicasUnavailable](KubeStatefulSetReplicasUnavailable.md)의 해소 확인을 함께 본다.

## 관련

- [KubeStatefulSetReplicasUnavailable](KubeStatefulSetReplicasUnavailable.md), [DbConnectionAcquireTimeout](DbConnectionAcquireTimeout.md)
- 같이 뜰 수 있는 알람: `NodeDiskSpaceLow`, `NodeDiskWillFillIn24h` (warning, 이 알람이 떠 있으면 숨겨짐)

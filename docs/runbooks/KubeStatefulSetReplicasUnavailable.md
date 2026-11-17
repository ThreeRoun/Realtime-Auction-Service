---
alert: KubeStatefulSetReplicasUnavailable
severity: critical
service: kubernetes
---

# KubeStatefulSetReplicasUnavailable

## 의미와 영향

StatefulSet의 replica 중 Ready가 아닌 것이 5분 이상 있다. 이 프로젝트의 StatefulSet은 **DB(`auction/auction-db`, Postgres) 하나뿐**이다.

- DB가 Ready가 아니면 엔진의 readiness도 실패해 **모든 API가 실패**한다. 엔진이 재시작되면 기동 자체가 실패한다(Flyway가 DB 접속 필요).
- DB 데이터는 PVC `db-data-auction-db-0`(서버 로컬 디스크)에 있다. **자동 백업이 없으므로 데이터를 잃는 조치는 절대 하지 않는다.**

## 확인

### C1. DB Pod 상태 `[도구: k8s_status]`

`auction-db-0` Pod의 phase, Ready, 재시작 횟수, 대기 사유, 마지막 종료 사유.

```bash
kubectl -n auction get pod auction-db-0 -o wide
kubectl -n auction describe pod auction-db-0 | tail -40
```

| 보이는 상태 | 의미 | 다음 |
| --- | --- | --- |
| `Running`, Ready 아님, 재시작 없음 | 기동 중(비정상 종료 후 복구 중일 수 있음) | C3 → 조치 C |
| 마지막 종료 사유 `OOMKilled` | 메모리 limit(1Gi) 초과 | 조치 B |
| `CrashLoopBackOff` | 기동 실패 반복 | C3, C4 |
| `Pending` | 스케줄 불가 또는 볼륨 문제 | C2 |
| `Terminating`에서 멈춤 / `Unknown` | 노드/컨테이너 런타임 문제 | 조치 D |

### C2. 이벤트 `[도구: k8s_status]`

`auction` 네임스페이스의 Warning 이벤트(`FailedMount`, `FailedScheduling`, `Unhealthy`, `BackOff`).

### C3. DB 로그 `[도구: query_loki]`

```logql
{namespace="auction", service="auction-db"}
```

| 로그에 보이는 것 | 의미 | 다음 |
| --- | --- | --- |
| `No space left on device`, `could not write` | 디스크 가득 참 | 조치 A |
| `database system was not properly shut down; automatic recovery in progress`, `redo starts` | 비정상 종료 후 복구 중 | 조치 C |
| `database system is ready to accept connections` | 기동 완료 (곧 Ready) | 지켜보기 |
| `PANIC`, `invalid page`, `could not read block` | 데이터 손상 의심 | 조치 E |
| `out of memory`, 종료 직전 로그 없음 | 메모리 부족 | 조치 B |

### C4. 서버 디스크와 메모리 `[도구: query_prometheus]`

```promql
node_filesystem_avail_bytes{job="node-exporter", mountpoint="/"} / node_filesystem_size_bytes{job="node-exporter", mountpoint="/"}
node_memory_MemAvailable_bytes{job="node-exporter"} / node_memory_MemTotal_bytes{job="node-exporter"}
max by (pod) (container_memory_working_set_bytes{job="kubelet-cadvisor", namespace="auction", container="postgres"})
```

## 조치

### A. 디스크 가득 참 → 디스크 Runbook으로

- **조건**: C3에 `No space left on device`, 또는 C4 디스크 여유 5% 미만
- **실행**: [NodeDiskSpaceCritical](NodeDiskSpaceCritical.md)으로 공간을 확보한다. 공간이 생기면 Postgres는 대개 스스로 복구된다.
  복구되지 않으면 Pod를 한 번 재시작한다(조치 D의 명령).

### B. OOMKilled → 메모리 limit 임시 상향

- **조건**: C1의 마지막 종료 사유가 `OOMKilled`
- **실행**:

  ```bash
  kubectl top node    # 서버 메모리 여유 먼저 확인
  kubectl -n auction patch statefulset auction-db --type=json \
    -p='[{"op":"replace","path":"/spec/template/spec/containers/0/resources/limits/memory","value":"1536Mi"}]'
  ```

- **주의**: StatefulSet 변경은 Pod를 다시 만든다(수십 초 다운). 임시 조치이므로 다음 배포 전에 매니페스트(`k8s/base/postgres.yaml`)를 고치는 PR을 올린다.
  메모리를 쓰는 원인(큰 쿼리 등)은 따로 분석한다.

### C. 복구 중 → 기다리기 (Pod를 지우지 않는다)

- **조건**: C3에 `automatic recovery in progress`/`redo starts`가 있고 아직 `ready to accept connections`가 없음
- **실행**: 기다린다. WAL 재적용 시간은 데이터 크기와 마지막 체크포인트에 따라 수십 초~수 분이다.
- **주의**: 복구 중에 Pod를 삭제하거나 재시작하면 복구가 처음부터 다시 시작된다. liveness 실패로 재시작이 반복되면 담당자와 상의한다.

### D. Pod가 멈춤(Terminating/Unknown/복구 후에도 Ready 안 됨) → Pod 재생성

- **조건**: C1에서 `Terminating`/`Unknown`으로 멈춰 있거나, 디스크 확보 후에도 Ready가 되지 않음. C3에 데이터 손상 징후는 없음
- **실행**:

  ```bash
  kubectl -n auction delete pod auction-db-0
  kubectl -n auction get pod auction-db-0 -w     # 다시 생성되어 Ready가 되는지
  ```

- **주의**
  - Pod만 지운다. **PVC(`db-data-auction-db-0`)나 StatefulSet은 지우지 않는다.** 데이터는 PVC에 남아 있다.
  - `--force --grace-period=0`은 쓰지 않는다. 같은 데이터 디렉터리를 두 프로세스가 동시에 열 위험이 있다.

### E. 데이터 손상 의심 → 즉시 담당자 호출

- **조건**: C3에 `PANIC`, `invalid page`, `could not read block` 등
- **실행**: 아무것도 지우거나 재시작하지 말고 담당자를 호출한다. DB가 읽기라도 되면 먼저 백업을 받는다.

  ```bash
  # 디스크가 부족하면 서버가 아닌 다른 곳(개인 PC 등)으로 받는다
  kubectl -n auction exec auction-db-0 -- sh -c 'pg_dump -U "$POSTGRES_USER" -d auction -Fc' > auction-$(date +%Y%m%d-%H%M).dump
  ```

## 해소 확인

```bash
kubectl -n auction get pod auction-db-0                      # READY 1/1
kubectl -n auction get pods -l app=auction-engine            # 엔진도 READY 1/1 (필요하면 rollout restart)
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/api/products   # 서버에서 200
```

DB가 복구된 뒤에도 엔진이 Ready가 되지 않으면 `kubectl -n auction rollout restart deploy/auction-engine`.

## 관련

- [NodeDiskSpaceCritical](NodeDiskSpaceCritical.md), [AuctionEngineDown](AuctionEngineDown.md), [DbConnectionAcquireTimeout](DbConnectionAcquireTimeout.md)
- 같이 뜰 수 있는 알람: `KubePodCrashLooping`, `KubeContainerOOMKilled`, `KubePodNotRunning` (warning, 이 알람이 떠 있으면 숨겨짐)

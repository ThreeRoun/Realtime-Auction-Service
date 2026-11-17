---
alert: AuctionEngineDown
severity: critical
service: auction-engine
---

# AuctionEngineDown

## 의미와 영향

Prometheus가 1분 이상 경매 엔진(auction-engine)의 지표를 가져오지 못했다. 엔진 Pod가 없거나, 죽었거나, 응답하지 못하는 상태다.

- 엔진은 replica 1개라 **모든 API(상품 조회, 로그인, 입찰)가 실패**한다.
- 이 알람이 뜨면 다른 엔진 알람(5xx, 지연 등)은 숨겨진다 (Alertmanager inhibit).

## 확인

### C1. 엔진 Pod 상태 `[도구: k8s_status]`

`auction` 네임스페이스, `app=auction-engine` Pod의 phase, Ready, 재시작 횟수, 대기 사유(waiting reason), 마지막 종료 사유(last terminated reason).

```bash
kubectl -n auction get pods -l app=auction-engine -o wide
kubectl -n auction describe pod -l app=auction-engine | tail -40
```

| 보이는 상태 | 의미 | 다음 |
| --- | --- | --- |
| Pod 없음 | Deployment가 0으로 줄었거나 생성 실패 | C2, C3 |
| `Pending` | 스케줄 불가 (노드 메모리 부족 등) | C2 → 조치 E |
| `ImagePullBackOff` / `ErrImagePull` | 이미지 못 받음 (태그 없음, GHCR 인증 만료) | C2 → 조치 D |
| `CrashLoopBackOff` | 기동 중 죽음 반복 | C3, C4, C5 |
| 마지막 종료 사유 `OOMKilled` | 메모리 limit(1Gi) 초과 | 조치 C |
| `Running`인데 Ready 아님, 재시작 증가 | 응답 멈춤 → liveness 실패로 재시작 중 | C4 → 조치 B |
| `Running`, Ready, 재시작 없음 | 엔진은 정상, Prometheus 수집 문제 | C6 |

### C2. 이벤트 `[도구: k8s_status]`

`auction` 네임스페이스 최근 이벤트 중 Warning(`FailedScheduling`, `BackOff`, `Failed`, `Unhealthy`, `FailedMount`).

```bash
kubectl -n auction get events --sort-by=.lastTimestamp | tail -20
```

### C3. 최근 배포 여부 `[도구: rollout_history]`

`auction-engine` Deployment의 ReplicaSet 목록에서, 알람 시작 30분 전 이내에 새 ReplicaSet(새 이미지 SHA)이 생겼는지 본다.

```bash
kubectl -n auction rollout history deploy/auction-engine
kubectl -n auction get rs -l app=auction-engine -o wide --sort-by=.metadata.creationTimestamp
```

배포 직후부터 문제가 시작됐다면 그 배포가 원인일 가능성이 높다 → 조치 A.

### C4. 엔진 에러 로그 `[도구: query_loki]`

알람 시작 전후 10분의 ERROR 로그. 기동 실패라면 마지막 로그에 원인이 찍힌다 (DB 연결 실패, Flyway 마이그레이션 실패, 설정 누락 등).

```logql
{namespace="auction", service="auction-engine"} | json | level="ERROR"
```

```bash
# 사람: 직전에 죽은 컨테이너의 로그
kubectl -n auction logs deploy/auction-engine --previous --tail=100
```

### C5. DB 상태 `[도구: k8s_status]`

엔진은 기동할 때 DB에 접속해 Flyway 마이그레이션을 확인하므로, **DB가 Ready가 아니면 엔진도 기동하지 못한다.**
`auction-db` StatefulSet과 `auction-db-0` Pod가 Ready인지 본다. Ready가 아니면 DB가 근본 원인 → 조치 F.

### C6. Prometheus 수집 상태 `[도구: query_prometheus]`

엔진 Pod가 정상인데 알람이 떴다면 수집 쪽 문제다.

```promql
up{job="auction-engine"}
```

```bash
# 사람: Prometheus Targets의 에러 메시지
kubectl -n monitoring port-forward svc/prometheus 9090:9090
# 브라우저 http://localhost:9090/targets 에서 auction-engine 행의 Error 확인
```

## 조치

### A. 직전 배포로 인한 장애 → 이전 버전으로 롤백

- **조건**: C3에서 알람 직전에 새 배포가 있었고, C4 로그가 새 코드/설정 문제를 가리킴
- **실행**:

  ```bash
  kubectl -n auction rollout undo deploy/auction-engine
  kubectl -n auction rollout status deploy/auction-engine --timeout=300s
  ```

- **주의**
  - 롤백해도 다음 baseline 머지 때 같은 코드가 다시 배포된다. 원인 PR을 revert하는 PR을 바로 올린다.
  - 그 배포에 DB 마이그레이션(`auction-engine/src/main/resources/db/migration`)이 포함됐다면, 컬럼 삭제/이름 변경 여부를 먼저 본다.
    이전 버전이 새 스키마와 맞지 않으면 롤백한 버전도 기동에 실패한다 (`ddl-auto=validate`). 이때는 담당자와 상의한다.

### B. 응답 멈춤 (Running인데 liveness 실패) → 스레드 덤프 후 재시작

- **조건**: C1에서 Running인데 Ready 아님 / liveness 실패로 재시작 반복, C3에서 최근 배포 없음
- **실행**:

  ```bash
  # 1) 증거: 스레드 덤프를 표준출력(=Loki)에 남긴다 (JRE 이미지라 jcmd 없음, SIGQUIT 사용)
  kubectl -n auction exec deploy/auction-engine -- kill -3 1
  # 2) 재시작
  kubectl -n auction rollout restart deploy/auction-engine
  kubectl -n auction rollout status deploy/auction-engine --timeout=300s
  ```

- **주의**: 스레드 덤프는 Loki에서 `{namespace="auction", service="auction-engine"} |= "Full thread dump"`로 찾는다. 원인 분석용으로 이슈에 첨부한다(개인정보 마스킹).

### C. OOMKilled 반복 → 메모리 limit 임시 상향

- **조건**: C1의 마지막 종료 사유가 `OOMKilled`이고 재시작이 반복됨
- **실행**:

  ```bash
  # 서버 메모리 여유 먼저 확인 (Grafana "클러스터 · 서버" 대시보드 또는 아래)
  kubectl top node
  kubectl -n auction set resources deploy/auction-engine -c auction-engine --limits=memory=1536Mi
  ```

- **주의**
  - 힙은 limit의 75%(`MaxRAMPercentage=75`)로 잡히므로 limit을 올리면 힙도 커진다.
  - 임시 조치다. 다음 배포 때 매니페스트 값(1Gi)으로 돌아가므로, 메모리 누수 여부를 분석하고 매니페스트를 고치는 PR을 올린다.
  - 서버 메모리가 부족하면(NodeMemoryLow) 다른 Pod가 밀려날 수 있다. 이때는 담당자와 상의한다.

### D. 이미지 pull 실패 → GHCR pull Secret 갱신

- **조건**: C1/C2에서 `ImagePullBackOff`/`ErrImagePull`, 이벤트에 `401`/`unauthorized`/`not found`
- **실행**:
  - `unauthorized`: PAT 만료. [k8s/README.md](../../k8s/README.md) "사전 준비 2"의 `ghcr-secret` 생성 명령으로 새 PAT를 넣는다 (`--dry-run=client -o yaml | kubectl apply -f -`).
  - `not found`: 이미지 태그(SHA)가 GHCR에 없음. 배포 워크플로의 build-and-push job이 실패했는지 확인하고 조치 A로 이전 버전으로 되돌린다.

### E. 스케줄 불가(Pending) → 노드 자원 확인 후 담당자 판단

- **조건**: C1이 `Pending`, C2에 `FailedScheduling` (Insufficient memory/cpu)
- **실행**: `kubectl describe node | grep -A8 "Allocated resources"`로 요청량을 보고, 담당자와 어떤 Pod의 requests를 줄일지 정한다.
  자동으로 할 수 있는 안전한 조치가 없다.

### F. DB 장애가 원인 → DB Runbook으로

- **조건**: C5에서 auction-db가 Ready 아님
- **실행**: [KubeStatefulSetReplicasUnavailable](KubeStatefulSetReplicasUnavailable.md)을 따른다. DB가 복구되면 엔진은 재시작으로 자동 복구된다.

### G. 수집 문제(엔진은 정상) → Prometheus 확인

- **조건**: C1에서 엔진 Pod가 Running/Ready, 재시작 없음 (C6)
- **실행**: 실제 서비스 영향은 없다. Prometheus Targets의 에러를 보고, `prometheus.io/*` annotation이나 Prometheus Pod 상태를 확인한다.
  `kubectl -n monitoring get pods`, `kubectl -n monitoring logs deploy/prometheus --tail=50`

## 해소 확인

```bash
kubectl -n auction get pods -l app=auction-engine        # Running, READY 1/1
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/api/products   # 서버에서 200
```

Prometheus에서 `up{job="auction-engine"} == 1`이 되면 1~2분 뒤 Discord에 RESOLVED가 온다.

## 관련

- 원인이 DB면 [KubeStatefulSetReplicasUnavailable](KubeStatefulSetReplicasUnavailable.md), 디스크면 [NodeDiskSpaceCritical](NodeDiskSpaceCritical.md)
- 같이 뜰 수 있는 알람: `KubePodCrashLooping`, `KubeContainerOOMKilled`, `KubeDeploymentReplicasUnavailable` (warning)

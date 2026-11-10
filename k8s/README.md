# Kubernetes 배포 구성

`docker-compose.prod.yml`과 같은 구성(경매 엔진 / 실시간 중계 / Frontend / Redis / Postgres)을 Kubernetes로 옮긴 매니페스트.
단일 노드 k3s(학교 서버)를 기준으로 작성했다.

## 구조

```text
k8s/
├── base/                    # 환경 공통 리소스 (namespace: auction)
│   ├── namespace.yaml
│   ├── configmap.yaml       # 비밀이 아닌 환경변수
│   ├── postgres.yaml        # StatefulSet + PVC(5Gi), Service: auction-db
│   ├── redis.yaml           # Deployment, Service: auction-redis
│   ├── auction-engine.yaml  # Deployment(replica 1) + 상품 이미지 PVC(2Gi)
│   ├── realtime-relay.yaml  # Deployment(replica 2)
│   ├── frontend.yaml        # Deployment(replica 2)
│   ├── ingress.yaml         # 외부 진입점 → frontend(nginx)
│   └── kustomization.yaml
├── overlays/prod/           # 이미지 태그 지정
└── monitoring/              # 모니터링·로깅·알람 스택 (namespace: monitoring) - 앱과 따로 적용
    ├── prometheus.yaml      # 지표 수집 + 알람 규칙 평가 (PVC 5Gi, 7일 보존)
    ├── alertmanager.yaml    # 알람 → Discord 발송
    ├── node-exporter.yaml   # 서버 CPU/메모리/디스크 지표 (DaemonSet)
    ├── kube-state-metrics.yaml # Pod 재시작/Pending 등 k8s 상태 지표
    ├── loki.yaml            # 로그 저장 (PVC 5Gi, 7일 보존)
    ├── alloy.yaml           # Pod 로그 수집 → Loki
    ├── grafana.yaml         # 조회 UI (PVC 1Gi) + Ingress /grafana
    ├── config/              # 각 도구 설정 파일 (configMapGenerator로 ConfigMap 생성)
    │   ├── rules/           # 알람 규칙 + 단위 테스트 (compose와 공용)
    │   └── alertmanager.yml # 알림 라우팅 (compose와 공용)
    └── kustomization.yaml
```

Service 이름을 compose의 컨테이너 이름(`auction-db`, `auction-redis`, `auction-engine`, `realtime-relay`)과 같게 맞춰서,
`frontend/nginx.conf`와 각 서비스의 환경변수를 수정하지 않고 그대로 쓴다.

트래픽 흐름: `Ingress(:80)` → `frontend(nginx)` → `/api`, `/images`는 `auction-engine`, `/ws`는 `realtime-relay`
(`/grafana`만 모니터링 스택의 Grafana로 간다)

## 사전 준비 (최초 1회)

### 1. k3s 설치

```bash
curl -sfL https://get.k3s.io | sh -
sudo kubectl get nodes
```

k3s에는 Traefik(Ingress Controller)과 local-path(기본 StorageClass)가 포함되어 있어 별도 설치가 필요 없다.

`/etc/rancher/k3s/k3s.yaml`은 root만 읽을 수 있으므로, kubectl을 쓸 계정(본인, self-hosted runner 계정)마다 복사해 둔다.
클러스터 관리자 권한이 담긴 파일이라 `--write-kubeconfig-mode 644`로 모두에게 여는 방식은 쓰지 않는다.

```bash
mkdir -p ~/.kube
sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
sudo chown "$(id -u):$(id -g)" ~/.kube/config
chmod 600 ~/.kube/config
kubectl get nodes
```

### 2. 네임스페이스와 Secret 생성

Secret은 저장소에 커밋하지 않고 서버에서 직접 만든다.

```bash
kubectl create namespace auction

# DB 계정과 JWT 서명 키
kubectl -n auction create secret generic auction-secret \
  --from-literal=POSTGRES_USER=auction \
  --from-literal=POSTGRES_PASSWORD='<DB 비밀번호>' \
  --from-literal=JWT_SECRET="$(openssl rand -base64 32)"

# GHCR private 이미지 pull용 (read:packages 권한이 있는 PAT)
kubectl -n auction create secret docker-registry ghcr-secret \
  --docker-server=ghcr.io \
  --docker-username='<GitHub 사용자명>' \
  --docker-password='<PAT>'
```

> `POSTGRES_PASSWORD`는 DB를 처음 초기화할 때만 적용된다. 이미 데이터가 있는 PVC에서는 Secret만 바꿔도 비밀번호가 바뀌지 않는다.

### 3. 모니터링 스택용 네임스페이스와 Secret 생성

Grafana 관리자 계정도 저장소에 커밋하지 않고 서버에서 직접 만든다. 이 Secret이 없으면 CI의 모니터링 배포 job이 실패한다.

```bash
kubectl create namespace monitoring

kubectl -n monitoring create secret generic grafana-admin \
  --from-literal=admin-user=admin \
  --from-literal=admin-password="$(openssl rand -base64 18)"

# 생성된 비밀번호 확인
kubectl -n monitoring get secret grafana-admin -o jsonpath='{.data.admin-password}' | base64 -d; echo
```

> Grafana는 처음 기동할 때만 이 값으로 관리자 계정을 만든다. 이후 비밀번호를 바꾸려면 Grafana UI에서 바꾸거나
> `kubectl -n monitoring exec deploy/grafana -- grafana cli admin reset-admin-password '<새 비밀번호>'`를 쓴다.

알람을 받을 Discord 채널의 webhook URL도 Secret으로 만든다
(Discord 채널 설정 → 연동 → 웹후크 → 새 웹후크 → URL 복사).
이 Secret이 없어도 Alertmanager는 뜨지만 알림이 발송되지 않는다 (CI에 경고 표시).

```bash
kubectl -n monitoring create secret generic alertmanager-discord \
  --from-literal=discord-webhook-url='https://discord.com/api/webhooks/...'
```

> URL을 바꾼 뒤에는 `kubectl -n monitoring rollout restart deploy/alertmanager`로 반영한다.

## 배포

### 자동 배포 (CI)

`baseline` 브랜치에 머지되면 `.github/workflows/deploy-baseline.yml`이 다음 순서로 배포한다.

1. 3개 서비스 이미지를 빌드해 GHCR에 `<커밋 SHA>`, `baseline-latest` 태그로 push
2. 서버의 self-hosted runner가 배포 직전 이미지를 기록한 뒤, 이미지 태그를 커밋 SHA로 바꿔 `kubectl apply`
3. `rollout status`로 3개 Deployment 롤아웃 완료 대기 (최대 5분)
4. `http://localhost/`, `http://localhost/api/products` 스모크 테스트 (Ingress 경유)
5. 2~4 중 하나라도 실패하면 **배포 직전 이미지로 자동 롤백**

롤링 업데이트가 `maxUnavailable: 0`이라 새 Pod가 준비되지 않으면 기존 Pod가 계속 요청을 받는다.
실행 결과와 배포된 이미지는 Actions 실행 화면의 Summary에서 확인할 수 있다.

앱 배포가 끝나면 별도 job(`deploy-monitoring`)이 모니터링 스택(`k8s/monitoring`)을 적용한다.

- 앱 배포와 분리돼 있어 모니터링 쪽이 실패해도 **앱은 롤백되지 않는다**.
- 앱 배포가 실패해도 모니터링 스택은 적용한다 (장애 상황 확인용).
- 스모크 테스트: `/grafana/api/health` 응답 확인. Prometheus가 경매 엔진 지표를 못 가져오면 경고만 남긴다.

### 수동 배포

```bash
kubectl apply -k k8s/overlays/prod
kubectl -n auction get pods
```

특정 커밋 버전으로 배포하려면 prod overlay를 감싸는 임시 overlay로 이미지 태그만 바꿔서 적용한다 (CI와 같은 방식, `kustomize` 바이너리 불필요).

```bash
SHA=<커밋 SHA>
mkdir -p .deploy && cat > .deploy/kustomization.yaml <<EOF
resources:
  - ../k8s/overlays/prod
images:
  - name: ghcr.io/threeroun/auction-engine
    newTag: "$SHA"
  - name: ghcr.io/threeroun/realtime-relay
    newTag: "$SHA"
  - name: ghcr.io/threeroun/frontend
    newTag: "$SHA"
EOF
kubectl apply -k .deploy && rm -r .deploy
```

## 모니터링·로깅 (k8s/monitoring)

```text
auction-engine     ─┐
node-exporter      ─┼─(지표)─▶ Prometheus ─(알람 규칙)─▶ Alertmanager ─▶ Discord
kube-state-metrics ─┘              │
모든 Pod ─(로그, Kubernetes API)─▶ Alloy ─▶ Loki ─┴─▶ Grafana (/grafana)
```

| 구성 요소 | 역할 | 비고 |
| --- | --- | --- |
| Prometheus | `auction`, `monitoring` 네임스페이스에서 `prometheus.io/scrape: "true"` annotation이 붙은 Pod 지표 수집, 알람 규칙 평가 | 7일 또는 4GB 보존 |
| Alertmanager | 알람 그룹핑/억제 후 Discord 발송 | webhook URL은 `alertmanager-discord` Secret |
| node-exporter | 서버 CPU/메모리/디스크 지표 | DaemonSet, hostNetwork 미사용(포트 외부 비노출) |
| kube-state-metrics | Pod 재시작, Pending, replica 준비 상태 등 k8s 객체 상태 지표 | 읽기 전용 ClusterRole |
| Alloy | 모든 네임스페이스의 Pod 로그를 Kubernetes API(`pods/log`)로 읽어 Loki로 전송 | 노드 디스크 마운트 없음, replica 1 |
| Loki | 로그 저장 | 7일 보존 |
| Grafana | 지표·로그·알람 조회, 데이터 소스(Prometheus, Loki, Alertmanager) 자동 등록 | `http://<서버>/grafana` |

수동 적용:

```bash
kubectl apply -k k8s/monitoring
kubectl -n monitoring get pods
```

**지표 수집 대상 추가**: Pod 템플릿에 annotation만 붙이면 Prometheus가 자동으로 찾는다 (`k8s/base/auction-engine.yaml` 참고).

```yaml
annotations:
  prometheus.io/scrape: "true"
  prometheus.io/port: "8080"
  prometheus.io/path: /actuator/prometheus
```

**라벨**: compose 환경과 같은 이름을 쓰므로 `monitoring/README.md`, `logging/README.md`의 PromQL/LogQL 예시를 그대로 쓸 수 있다.

- 지표: `job`(= Pod의 `app` 라벨, 예: `auction-engine`), `namespace`, `pod`
- 로그: `service`(= Pod의 `app` 라벨, 없으면 `app.kubernetes.io/name` / `k8s-app`), `namespace`, `pod`, `container`

**설정 변경**: `k8s/monitoring/config/`의 파일을 고치면 ConfigMap 이름(해시)이 바뀌어 해당 Pod가 자동으로 재시작된다.
알람 규칙(`config/rules/`)과 `config/alertmanager.yml`은 로컬 compose도 같은 파일을 마운트해서 쓴다.
수집 설정(`prometheus.yml`, `config.alloy`)은 환경마다 수집 방식이 달라 compose용(`monitoring/`, `logging/`)과 별개다.

### 알람

규칙 파일은 `config/rules/`에 있고, 수정 후 PR 전에 단위 테스트를 돌린다 (CI `Lint / monitoring`에서도 실행).

```bash
docker run --rm -v "$PWD/k8s/monitoring/config/rules":/r -w /r --entrypoint sh prom/prometheus:v3.0.1 \
  -c 'promtool check rules *.rules.yml && cd tests && promtool test rules *.test.yml'
```

| 파일 | 알람 | 심각도 | 조건 |
| --- | --- | --- | --- |
| `auction-engine.rules.yml` | 엔진 다운, 5xx, 입찰 실패, DB 커넥션, 지연, JVM | | 목록은 [monitoring/README.md](../monitoring/README.md#알람-alerting) |
| `node.rules.yml` | `NodeDiskSpaceLow` | warning | 디스크 여유 < 15% (5분) |
| | `NodeDiskSpaceCritical` | critical | 디스크 여유 < 5% (2분) - DB 쓰기 실패 직전 |
| | `NodeDiskWillFillIn24h` | warning | 최근 6시간 추세로 24시간 내 가득 참 (여유 < 40%일 때) |
| | `NodeMemoryLow` | warning | 사용 가능 메모리 < 10% (5분) |
| | `NodeCpuHigh` | warning | CPU 사용률 > 90% (15분) |
| `kubernetes.rules.yml` | `KubePodCrashLooping` | warning | CrashLoopBackOff 10분 지속 (기동 중 일시적 재시작 제외) |
| | `KubeContainerOOMKilled` | warning | 메모리 limit 초과로 재시작 |
| | `KubePodNotRunning` | warning | Pending/Failed/Unknown 10분 지속 |
| | `KubeDeploymentReplicasUnavailable` | warning | 준비 안 된 replica 10분 지속 |
| | `KubeStatefulSetReplicasUnavailable` | critical | DB(StatefulSet) Ready 아님 5분 |
| `monitoring.rules.yml` | `MonitoringTargetDown` | warning | 엔진 외 수집 대상(node-exporter 등) 5분 응답 없음 |
| | `AlertmanagerNotificationsFailing` | warning | Discord 발송 실패 10분 지속 (UI/Grafana에서 확인) |

- Kubernetes 알람은 `auction`, `monitoring` 네임스페이스만 대상이다.
- 같은 `service` 라벨(`auction-engine` / `node` / `kubernetes` / `monitoring`)에 critical이 떠 있으면 warning은 억제된다.
- 테스트 알람 발송: `kubectl -n monitoring port-forward svc/alertmanager 9093` 후
  [monitoring/README.md](../monitoring/README.md)의 "Discord 알림 연결" 4번 `curl` 명령을 그대로 쓴다.

## 자주 쓰는 명령

```bash
# 배포 진행 상황 / 롤백
kubectl -n auction rollout status deploy/auction-engine
kubectl -n auction rollout undo deploy/auction-engine

# 로그
kubectl -n auction logs -f deploy/auction-engine
kubectl -n auction logs -l app=realtime-relay --prefix

# Secret 변경 후 반영 (Secret은 바뀌어도 Pod가 자동 재시작되지 않음)
kubectl -n auction rollout restart deploy/auction-engine
```

## 현재 제약

- **auction-engine은 replica 1개로 고정**: 상품 이미지를 로컬 디스크(ReadWriteOnce PVC)에 저장하고, `@Scheduled` 작업이 모든 Pod에서 실행되기 때문.
- **realtime-relay 헬스체크는 TCP 포트 확인 수준**: HTTP 헬스 엔드포인트가 필요하다(#103). auction-engine은 Actuator probe(`/actuator/health/liveness`, `/actuator/health/readiness`)를 쓴다.
- **모니터링 스택은 단일 replica**: Prometheus/Loki/Grafana는 PVC(ReadWriteOnce)를 쓰고 `Recreate` 전략이라
  설정 변경으로 재시작되는 동안(수십 초) 조회와 수집이 잠깐 끊긴다.
- **realtime-relay는 지표 미노출**: relay 자체 지표(연결 수 등)는 없고, Pod 상태(재시작/Pending)만 kube-state-metrics로 본다.
- **서버 네트워크 지표 없음**: node-exporter가 hostNetwork를 쓰지 않아(포트 외부 비노출) 네트워크 지표는 서버 NIC 기준이 아니다.
- **compose 배포와 동시 운영 금지**: 같은 서버에서 compose와 k3s를 함께 띄우면 DB가 두 벌이 된다. 전환 시 기존 compose DB를 `pg_dump`로 옮긴 뒤 compose를 내린다.

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
└── overlays/prod/           # 이미지 태그 지정
```

Service 이름을 compose의 컨테이너 이름(`auction-db`, `auction-redis`, `auction-engine`, `realtime-relay`)과 같게 맞춰서,
`frontend/nginx.conf`와 각 서비스의 환경변수를 수정하지 않고 그대로 쓴다.

트래픽 흐름: `Ingress(:80)` → `frontend(nginx)` → `/api`, `/images`는 `auction-engine`, `/ws`는 `realtime-relay`

## 사전 준비 (최초 1회)

### 1. k3s 설치

```bash
curl -sfL https://get.k3s.io | sh -
sudo kubectl get nodes
```

k3s에는 Traefik(Ingress Controller)과 local-path(기본 StorageClass)가 포함되어 있어 별도 설치가 필요 없다.

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

## 배포

```bash
kubectl apply -k k8s/overlays/prod
kubectl -n auction get pods
```

특정 커밋 버전으로 배포하려면 이미지 태그를 바꾼 뒤 적용한다.

```bash
cd k8s/overlays/prod
kustomize edit set image \
  ghcr.io/threeroun/auction-engine=ghcr.io/threeroun/auction-engine:<커밋 SHA> \
  ghcr.io/threeroun/realtime-relay=ghcr.io/threeroun/realtime-relay:<커밋 SHA> \
  ghcr.io/threeroun/frontend=ghcr.io/threeroun/frontend:<커밋 SHA>
kubectl apply -k .
```

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
- **헬스체크는 TCP 포트 확인 수준**: auction-engine에 Actuator가 없고, realtime-relay는 HTTP 요청에 426만 돌려준다.
- **compose 배포와 동시 운영 금지**: 같은 서버에서 compose와 k3s를 함께 띄우면 DB가 두 벌이 된다. 전환 시 기존 compose DB를 `pg_dump`로 옮긴 뒤 compose를 내린다.
- CI 배포(`deploy-baseline.yml`)는 아직 compose 방식이다. 서버에 k3s를 설치한 뒤 `kubectl apply -k` 방식으로 전환한다.

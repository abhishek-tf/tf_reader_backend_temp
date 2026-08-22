#!/bin/bash
# Phase 13 - break one invariant at a time, prove the suite catches it, always revert.
set -uo pipefail
cd "$(dirname "$0")"

MAIN=src/main/java/com/tf/reader
BACKUP=$(mktemp -d)
trap 'echo "--- restoring sources ---"; cp -R "$BACKUP"/. src/ 2>/dev/null; rm -rf "$BACKUP"' EXIT

save() { mkdir -p "$BACKUP/$(dirname "${1#src/}")"; cp "$1" "$BACKUP/${1#src/}"; }
restore() { cp "$BACKUP/${1#src/}" "$1"; }

run() { # run <test-selector>; prints PASS/FAIL of the maven run
  if ./mvnw -o -q test -Dtest="$1" -Dsurefire.failIfNoSpecifiedTests=false \
      -Dspring.docker.compose.enabled=false > /tmp/probe-out.txt 2>&1; then
    echo "SUITE-PASSED"
  else
    echo "SUITE-FAILED"
  fi
}

probe() { # probe <name> <test> <expected: SUITE-FAILED>
  local name=$1 test=$2
  local result
  result=$(run "$test")
  if [ "$result" = "SUITE-FAILED" ]; then
    echo "✅ $name → $test FAILED as required"
    grep -oE "[A-Za-z]+Test[.a-zA-Z0-9_]*:[0-9]+" /tmp/probe-out.txt | head -3 | sed 's/^/      /'
  else
    echo "❌ $name → $test STILL PASSED - the guard does not guard"
  fi
}

for f in "$MAIN/auth/AuthController.java" "$MAIN/auth/SecurityConfig.java" \
         "$MAIN/auth/security/CurrentUserJwtConverter.java" \
         "$MAIN/auth/security/TnfJwtValidator.java" \
         "$MAIN/auth/authorization/AuthorizationService.java" \
         "$MAIN/auth/saml/SamlAuthenticationSuccessHandler.java"; do save "$f"; done

echo "=========== P1: a new endpoint under a public prefix"
python3 - <<'EOF'
p="src/main/java/com/tf/reader/auth/AuthController.java"
s=open(p).read()
s=s.replace('\t@PostMapping("/saml/start")',
 '\t@GetMapping("/../../../saml2/probe")\n\tpublic String probe() { return "leaked"; }\n\n\t@PostMapping("/saml/start")')
open(p,"w").write(s)
EOF
probe "temporary public endpoint" "AuthorizationCoverageTest"
restore "$MAIN/auth/AuthController.java"

echo "=========== P2: logging the presented token"
python3 - <<'EOF'
p="src/main/java/com/tf/reader/auth/security/CurrentUserJwtConverter.java"
s=open(p).read()
s=s.replace("\tpublic AbstractAuthenticationToken convert(Jwt jwt) {",
 "\tpublic AbstractAuthenticationToken convert(Jwt jwt) {\n"
 "\t\torg.slf4j.LoggerFactory.getLogger(getClass()).info(\"debugging token {}\", jwt.getTokenValue());")
open(p,"w").write(s)
EOF
probe "unsafe token logging" "SensitiveDataLoggingTest"
restore "$MAIN/auth/security/CurrentUserJwtConverter.java"

echo "=========== P3+P4: a controller that authorizes, and a second JWT parser"
python3 - <<'EOF'
p="src/main/java/com/tf/reader/auth/AuthController.java"
s=open(p).read()
s=s.replace("\t@GetMapping(\"/me\")",
 "\tprivate static final Class<?> P1 = com.tf.reader.auth.authorization.AuthorizationService.class;\n"
 "\tprivate static final Class<?> P2 = org.springframework.security.oauth2.jwt.JwtDecoder.class;\n\n"
 "\t@GetMapping(\"/me\")")
open(p,"w").write(s)
EOF
probe "controller-side authorization + duplicate JWT parser" "SecurityArchitectureTest"
restore "$MAIN/auth/AuthController.java"

echo "=========== P5: the null == null institution trap"
python3 - <<'EOF'
p="src/main/java/com/tf/reader/auth/authorization/AuthorizationService.java"
s=open(p).read()
start=s.index("\tpublic void requireSameInstitution")
end=s.rindex("}")
s=s[:start]+('\tpublic void requireSameInstitution(CurrentUser currentUser, String resourceInstitutionId) {\n'
 '\t\tif (currentUser != null\n'
 '\t\t\t\t&& java.util.Objects.equals(currentUser.institutionId(), resourceInstitutionId)) {\n'
 '\t\t\treturn;\n'
 '\t\t}\n'
 '\t\tthrow new ApiException(ErrorCode.WRONG_INSTITUTION, "Not yours.");\n'
 '\t}\n')+s[end:]
open(p,"w").write(s)
EOF
probe "weakened institution isolation" "AuthorizationServiceTest"
restore "$MAIN/auth/authorization/AuthorizationService.java"

echo "=========== P6: role enforcement removed"
python3 - <<'EOF'
p="src/main/java/com/tf/reader/auth/authorization/AuthorizationService.java"
s=open(p).read()
s=s.replace("\t\tif (!hasAnyRole(currentUser, permitted)) {", "\t\tif (false) {")
open(p,"w").write(s)
EOF
probe "role enforcement removed" "AuthorizationServiceTest"
restore "$MAIN/auth/authorization/AuthorizationService.java"

echo "=========== P7: the API chain made stateful again"
python3 - <<'EOF'
p="src/main/java/com/tf/reader/auth/SecurityConfig.java"
s=open(p).read()
s=s.replace("""\t\t\t\t.sessionManagement(session -> session
\t\t\t\t\t\t.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
""","")
open(p,"w").write(s)
EOF
probe "session may authenticate the API" "StatelessApiTest"
restore "$MAIN/auth/SecurityConfig.java"

echo "=========== P8: type/institution agreement removed"
python3 - <<'EOF'
p="src/main/java/com/tf/reader/auth/security/TnfJwtValidator.java"
s=open(p).read()
start=s.index("\t\tboolean hasInstitution =")
end=s.index("\t\treturn OAuth2TokenValidatorResult.success();")
s=s[:start]+s[end:]
open(p,"w").write(s)
EOF
probe "type/institution agreement removed" "TnfJwtValidatorTest"
restore "$MAIN/auth/security/TnfJwtValidator.java"

echo "=========== P9: the sign-in session left alive"
python3 - <<'EOF'
p="src/main/java/com/tf/reader/auth/saml/SamlAuthenticationSuccessHandler.java"
s=open(p).read()
s=s.replace("""\t\tfinally {
\t\t\tdiscardTheSignInSession(request);
\t\t}
""","")
open(p,"w").write(s)
EOF
probe "sign-in session left alive" "SamlAuthenticationSuccessHandlerTest"
restore "$MAIN/auth/saml/SamlAuthenticationSuccessHandler.java"

echo "=========== all probes done, sources restored"

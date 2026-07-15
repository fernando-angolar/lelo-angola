-- Seed do utilizador administrador inicial.
--
-- Credenciais (APENAS para arranque/dev — trocar a password em produção):
--   email:    admin@lelo.ao
--   password: Admin@1234
--
-- O hash abaixo é BCrypt (custo 10) de "Admin@1234", compatível com
-- BCryptPasswordEncoder do Spring Security. Em produção, criar o admin via
-- processo seguro e nunca versionar credenciais reais.
--
-- Idempotente: se já existir um utilizador com este email, não faz nada.

INSERT INTO users (id, email, password, full_name, phone, enabled, created_at, version)
VALUES (
    gen_random_uuid(),
    'admin@lelo.ao',
    '$2a$10$3Itzv/lC3cmTA9.j0yuCt.OkUQKT8fZ3S2CTn02uoYsP4WbY5PySa',
    'Administrador Lelo',
    NULL,
    TRUE,
    NOW(),
    0
)
ON CONFLICT (email) DO NOTHING;

INSERT INTO user_roles (user_id, role)
SELECT id, 'ROLE_ADMIN'
FROM users
WHERE email = 'admin@lelo.ao'
ON CONFLICT (user_id, role) DO NOTHING;

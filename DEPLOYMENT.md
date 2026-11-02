# Guía de Despliegue en VPS para MoonOrbit Backend

Esta guía detalla el despliegue del backend de MoonOrbit en cualquier servidor VPS (Ubuntu/Debian/AlmaLinux/etc.) con **PostgreSQL**, soporte para **WebSockets** y certificados **SSL**.

---

## Opción 1: Despliegue Automatizado con Docker Compose (Recomendada)

Es la forma más rápida y aislada. Levanta PostgreSQL y la API Node.js con un solo comando.

### 1. Requisitos Previos en el VPS
```bash
# Actualizar el sistema e instalar Docker y Docker Compose
sudo apt update && sudo apt upgrade -y
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
```

### 2. Clonar el repositorio y configurar variables
```bash
git clone <tu-repositorio-url> /opt/moonorbit
cd /opt/moonorbit

# Copiar el archivo de variables si deseas personalizar credenciales
cp server/.env.example .env
```

### 3. Iniciar los servicios
```bash
docker compose up -d --build
```
> **Nota:** La base de datos ejecutará automáticamente el archivo `moonorbit-schema.sql` en su primer arranque gracias al volumen montado en `/docker-entrypoint-initdb.d/`.

### 4. Inicializar Datos Semilla y Crear Grupo Administrador
Para insertar los términos de servicio, la versión inicial de la app y crear tu primer grupo familiar:
```bash
# Inicializar términos y releases
docker compose exec app npx tsx scripts/init-db.ts

# Crear grupo familiar y usuario admin
docker compose exec app npx tsx scripts/create-group.ts
```

### 5. Verificar Estado y Logs
```bash
# Ver estado de los contenedores
docker compose ps

# Comprobar el endpoint de salud
curl http://localhost:3000/api/v1/health

# Ver logs en tiempo real
docker compose logs -f app
```

---

## Opción 2: Despliegue Nativo con Node.js y PM2

Si ya tienes PostgreSQL instalado en el VPS o usas una base de datos administrada (Supabase, Neon, RDS, etc.):

### 1. Instalar Node.js 22 y PM2
```bash
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs
sudo npm install -g pm2
```

### 2. Configurar la Base de Datos PostgreSQL
```bash
# Entrar a PostgreSQL
sudo -u postgres psql

# Crear la base de datos
CREATE DATABASE moonorbit;
\q

# Ejecutar el esquema DDL
sudo -u postgres psql -d moonorbit -f moonorbit-schema.sql
```

### 3. Configurar y Compilar el Backend
```bash
cd /opt/moonorbit/server
cp .env.example .env
nano .env  # Configura DATABASE_URL con tus credenciales

# Instalar dependencias y compilar TypeScript
npm ci
npm run build

# Inicializar datos semilla y crear grupo
npm run init-db
npm run create-group
```

### 4. Iniciar con PM2
```bash
pm2 start ecosystem.config.cjs --env production
pm2 save
pm2 startup
```

---

## Configuración de Nginx y Certificados SSL

Para exponer el backend de manera segura en internet con HTTPS y soporte completo para WebSockets (`wss://`):

### 1. Instalar Nginx y Certbot
```bash
sudo apt install -y nginx certbot python3-certbot-nginx
```

### 2. Configurar el Proxy Inverso
Copia la plantilla `nginx-moonorbit.conf` a la configuración de Nginx:
```bash
sudo cp /opt/moonorbit/nginx-moonorbit.conf /etc/nginx/sites-available/moonorbit
sudo ln -s /etc/nginx/sites-available/moonorbit /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default

# Editar y cambiar 'orbit.tu-dominio.com' por tu dominio real
sudo nano /etc/nginx/sites-available/moonorbit

# Probar la sintaxis de Nginx y recargar
sudo nginx -t
sudo systemctl reload nginx
```

### 3. Obtener Certificado SSL Gratuito (Let's Encrypt)
```bash
sudo certbot --nginx -d orbit.tu-dominio.com
```

---

## Conexión desde la Aplicación Android

Una vez que el servidor esté activo en tu VPS (ejemplo: `https://orbit.tudominio.com`):

1. Abre la aplicación **MoonOrbit** en tu dispositivo Android.
2. En la pantalla de Login, presiona el icono de **Servidor (DNS/Ajustes)** en la esquina superior derecha.
3. Ingresa la URL de tu servidor: `https://orbit.tudominio.com` y guarda.
4. Ingresa con las credenciales de tu usuario administrador o regístrate con el código de invitación generado.

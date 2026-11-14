# Marcas de arranque · Comunidad

Logotipos que los usuarios comparten para usarlos como **marca de arranque** en Scala Ambient
(Ajustes → Marca de arranque → Comunidad).

Estos archivos **no forman parte de la app**: la app los descarga al móvil solo cuando el usuario
elige uno. Cada marca y logotipo pertenece a su propietario.

## Añadir una marca

1. Sube aquí el logotipo en PNG con **fondo transparente**, apaisado (por ejemplo 1000 × 150 px) y de menos de 1 MB.
   El nombre del archivo solo puede llevar letras, números, puntos, guiones y guiones bajos.
2. Añade una línea a `marcas.json`:

   ```json
   { "id": "mimarca", "nombre": "Mi marca", "archivo": "mimarca.png", "monocromo": true }
   ```

   - `id`: nombre corto en minúsculas, sin espacios.
   - `monocromo`: `true` si el logotipo es de un solo color (la app lo pinta en blanco);
     `false` para conservar sus colores.

import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

import react from '@astrojs/react';
import { hasFeature } from './src/config.ts';

// https://astro.build/config
export default defineConfig({
    integrations: [
        starlight({
            title: 'LAPIS',
            social: {
                github: 'https://github.com/GenSpectrum/LAPIS',
            },
            customCss: ['./src/styles/custom.css'],
            sidebar: [
                {
                    label: 'Getting started',
                    items: [
                        {
                            label: 'Introduction',
                            link: '/getting-started/introduction',
                        },
                        {
                            label: 'Generate your request',
                            link: '/getting-started/generate-your-request',
                        },
                    ],
                },
                {
                    label: 'Concepts',
                    items: filterAvailableConcepts([
                        {
                            label: 'Data versions',
                            link: '/concepts/data-versions/',
                        },
                        {
                            label: 'Mutation filters',
                            link: '/concepts/mutation-filters/',
                        },
                        {
                            label: 'Pango lineage query',
                            link: '/concepts/pango-lineage-query/',
                            onlyIfFeature: 'sarsCoV2VariantQuery',
                        },
                        {
                            label: 'Response format',
                            link: '/concepts/response-format/',
                        },
                        {
                            label: 'Variant query',
                            link: '/concepts/variant-query/',
                            onlyIfFeature: 'sarsCoV2VariantQuery',
                        },
                    ]),
                },
                {
                    label: 'References',
                    items: [
                        {
                            label: 'Endpoints',
                            link: '/references/endpoints/',
                        },
                        {
                            label: 'Fields',
                            link: '/references/fields/',
                        },
                        {
                            label: 'Filters',
                            link: '/references/filters/',
                        },
                    ],
                },
            ],
        }),
        react(),
    ],
    // Process images with sharp: https://docs.astro.build/en/guides/assets/#using-sharp
    image: {
        service: {
            entrypoint: 'astro/assets/services/sharp',
        },
    },
});

/**
 * TODO Not sure if this is actually a good solution. The filtering now happens at compile time but ideally, it happens
 *   at runtime (so that the user/maintainer does not need to re-compile for their own config).
 */
function filterAvailableConcepts(pages) {
    return pages
        .filter((p) => !p.onlyIfFeature || hasFeature(p.onlyIfFeature))
        .map(({ label, link }) => ({ label, link }));
}

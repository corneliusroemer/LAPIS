import type { QueryTypeSelectionState } from './QueryTypeSelection';
import { CheckBoxesWrapper, LabeledCheckBox, LabelWrapper } from './styled-components';

const availableFormats = ['json', 'tsv', 'csv'] as const;
export type TabularOutputFormat = (typeof availableFormats)[number];

type Props = {
    queryType: QueryTypeSelectionState;
    format: TabularOutputFormat;
    onFormatChange: (format: TabularOutputFormat) => void;
};

export const OutputFormatSelection = ({ queryType, format, onFormatChange }: Props) => {
    return (
        <div>
            {queryType.selection === 'nucleotideSequences' || queryType.selection === 'aminoAcidSequences' ? (
                <>
                    For sequences, only <b>FASTA</b> is available as output format.
                </>
            ) : (
                <>
                    <LabelWrapper>Which format do you prefer?</LabelWrapper>
                    <CheckBoxesWrapper>
                        {availableFormats.map((f) => (
                            <LabeledCheckBox
                                label={f.toUpperCase()}
                                type='checkbox'
                                className='w-40'
                                checked={f === format}
                                onChange={() => onFormatChange(f)}
                            />
                        ))}
                    </CheckBoxesWrapper>
                </>
            )}
        </div>
    );
};
